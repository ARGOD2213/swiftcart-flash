package dev.mahindra.swiftcart.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

import dev.mahindra.swiftcart.IntegrationTest;
import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.inventory.Keys;
import dev.mahindra.swiftcart.ops.PaymentReconciler;
import dev.mahindra.swiftcart.ops.ReservationReaper;

/** Every way a payment can arrive: on time, twice, late, wrong, for nothing, or not at all. */
class PaymentFlowIT extends IntegrationTest {

    @Autowired
    ReservationReaper reaper;
    @Autowired
    PaymentReconciler reconciler;

    @Test
    void aCapturedPaymentConfirmsTheOrderAndQueuesFulfilment() throws Exception {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopper(), d, "TEE-M", 1);
        WebhookService.Result r = pay(o);

        assertThat(r.outcome()).isEqualTo(PaymentService.Outcome.PAID);
        assertThat(orderStatus(id(o))).isEqualTo("PAID");
        assertThat(events(o, "ORDER_PAID")).isEqualTo(1);
        assertThat(paymentStatuses(o)).containsExactly("CAPTURED");
        // a paid hold is never released by the reaper, even after its time is up
        expireNow(d, id(o));
        reaper.reap();
        assertThat(orderStatus(id(o))).isEqualTo("PAID");
        assertThat(stock(d, "TEE-M")).isEqualTo(9);
    }

    @Test
    void aWebhookDeliveredTwiceIsAppliedOnce() {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopperUnchecked(), d);
        SimulatorGateway.SignedWebhook hook = sim().pay(gatewayOrderId(o), "captured", o.path("amountMinor").asLong(), "INR", null);
        assertThat(webhooks.handle("simulator", hook.body(), hook.headers()).status()).isEqualTo("processed");
        assertThat(webhooks.handle("simulator", hook.body(), hook.headers()).status()).isEqualTo("duplicate");
        assertThat(events(o, "ORDER_PAID")).isEqualTo(1);
    }

    @Test
    void forgedWebhooksAreRejected() throws Exception {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopperUnchecked(), d);
        SimulatorGateway.SignedWebhook hook = sim().pay(gatewayOrderId(o), "captured", o.path("amountMinor").asLong(), "INR", null);
        mvc.perform(post("/webhooks/simulator").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "0".repeat(64)).content(hook.body()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_signature"));
        // a correct signature over a changed body fails too
        mvc.perform(post("/webhooks/simulator").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", hook.headers().getFirst("X-Razorpay-Signature"))
                        .content(hook.body().replace("\"amount\":" + o.path("amountMinor").asLong(), "\"amount\":1")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/webhooks/simulator").contentType(MediaType.APPLICATION_JSON)
                        .headers(hook.headers()).content(hook.body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("PAID"));
        mvc.perform(post("/webhooks/nope").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        assertThat(orderStatus(id(o))).isEqualTo("PAID");
    }

    @Test
    void aFailedPaymentKeepsTheOrderPayableAndALaterSuccessPays() {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopperUnchecked(), d);
        assertThat(pay(o, "failed", o.path("amountMinor").asLong()).outcome()).isEqualTo(PaymentService.Outcome.FAILURE_RECORDED);
        assertThat(orderStatus(id(o))).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.sql("select last_payment_error from orders where id = ?").param(UUID.fromString(id(o))).query(String.class).single())
                .contains("declined");
        assertThat(pay(o).outcome()).isEqualTo(PaymentService.Outcome.PAID);
    }

    @Test
    void anOutOfOrderFailureAfterCaptureChangesNothing() {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopperUnchecked(), d);
        SimulatorGateway.SignedWebhook captured = sim().pay(gatewayOrderId(o), "captured", o.path("amountMinor").asLong(), "INR", null);
        webhooks.handle("simulator", captured.body(), captured.headers());
        GatewayPayment p = captured.payment();
        GatewayPayment failedLater = new GatewayPayment("simulator", p.paymentId(), p.gatewayOrderId(), null, p.amountMinor(), "INR",
                GatewayPayment.Status.FAILED, "late failure");
        SimulatorGateway.SignedWebhook stale = sim().webhook(failedLater, "evt_" + UUID.randomUUID());
        webhooks.handle("simulator", stale.body(), stale.headers());

        assertThat(orderStatus(id(o))).isEqualTo("PAID");
        assertThat(paymentStatuses(o)).containsExactly("CAPTURED");
    }

    @Test
    void aSecondPaymentForAPaidOrderIsRefunded() {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopperUnchecked(), d);
        pay(o);
        WebhookService.Result second = pay(o);

        assertThat(second.outcome()).isEqualTo(PaymentService.Outcome.REFUNDING);
        assertThat(paymentStatuses(o)).containsExactlyInAnyOrder("CAPTURED", "REFUND_PENDING");
        assertThat(events(o, "REFUND_REQUESTED")).isEqualTo(1);
        assertThat(events(o, "ORDER_PAID")).isEqualTo(1);
    }

    @Test
    void aWrongAmountGoesToReviewAndIsNotFulfilled() {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopperUnchecked(), d);
        assertThat(pay(o, "captured", 100).outcome()).isEqualTo(PaymentService.Outcome.REVIEW);
        assertThat(orderStatus(id(o))).isEqualTo("REVIEW");
        assertThat(events(o, "ORDER_PAID")).isZero();
        assertThat(stock(d, "TEE-M")).isEqualTo(9); // units stay held while a human looks
    }

    @Test
    void aLatePaymentIsRescuedWhileUnitsRemain() {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopperUnchecked(), d);
        expireNow(d, id(o));
        reaper.reap();
        assertThat(orderStatus(id(o))).isEqualTo("EXPIRED");
        assertThat(stock(d, "TEE-M")).isEqualTo(10);

        assertThat(pay(o).outcome()).isEqualTo(PaymentService.Outcome.RESCUED);
        assertThat(orderStatus(id(o))).isEqualTo("PAID");
        assertThat(stock(d, "TEE-M")).isEqualTo(9);
    }

    @Test
    void aLatePaymentForUnitsSomeoneElseBoughtIsRefundedNotOversold() {
        Drop d = liveDrop(1, 2);
        JsonNode slow = order(shopperUnchecked(), d);
        expireNow(d, id(slow));
        reaper.reap();
        JsonNode fast = order(shopperUnchecked(), d);
        pay(fast);

        assertThat(pay(slow).outcome()).isEqualTo(PaymentService.Outcome.REFUNDING);
        assertThat(orderStatus(id(slow))).isEqualTo("EXPIRED");
        assertThat(paymentStatuses(slow)).containsExactly("REFUND_PENDING");
        assertThat(stock(d, "TEE-M")).isZero();
        assertThat(jdbc.sql("select count(*) from orders where drop_id = ? and status = 'PAID'").param(d.id()).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void aPaymentLandingAfterTheHoldEndedButBeforeTheReaperStillGetsItsUnits() {
        Drop d = liveDrop(1, 2);
        JsonNode o = order(shopperUnchecked(), d);
        expireNow(d, id(o)); // time is up, but the reaper hasn't run
        assertThat(pay(o).outcome()).isEqualTo(PaymentService.Outcome.PAID);
        reaper.reap();
        assertThat(orderStatus(id(o))).isEqualTo("PAID");
        assertThat(stock(d, "TEE-M")).isZero();
    }

    @Test
    void aPaymentForNoKnownOrderIsRefunded() {
        GatewayPayment orphan = new GatewayPayment("simulator", "sim_pay_" + UUID.randomUUID().toString().replace("-", ""),
                "sim_order_unknown" + UUID.randomUUID().toString().replace("-", ""), null, 50_000, "INR",
                GatewayPayment.Status.CAPTURED, null);
        SimulatorGateway.SignedWebhook hook = sim().webhook(orphan, "evt_" + UUID.randomUUID());
        assertThat(webhooks.handle("simulator", hook.body(), hook.headers()).outcome()).isEqualTo(PaymentService.Outcome.REFUNDING);
        assertThat(jdbc.sql("select status from payments where gateway_payment_id = ?").param(orphan.paymentId()).query(String.class).single())
                .isEqualTo("REFUND_PENDING");
        assertThat(jdbc.sql("select count(*) from outbox where group_key = ? and type = 'REFUND_REQUESTED'").param(orphan.paymentId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aLostWebhookIsFoundByTheReconciler() {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopperUnchecked(), d);
        sim().pay(gatewayOrderId(o), "captured", o.path("amountMinor").asLong(), "INR", null); // webhook never sent
        jdbc.sql("update orders set created_at = now() - interval '10 minutes' where id = ?").param(UUID.fromString(id(o))).update();

        assertThat(reconciler.reconcile()).isGreaterThanOrEqualTo(1);
        assertThat(orderStatus(id(o))).isEqualTo("PAID");
    }

    @Test
    void anAuthorizedPaymentIsCapturedWhenTheUnitsAreStillHeld() {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopperUnchecked(), d);
        WebhookService.Result r = pay(o, "authorized", o.path("amountMinor").asLong());
        assertThat(r.outcome()).isEqualTo(PaymentService.Outcome.CAPTURE_NEEDED);
        assertThat(orderStatus(id(o))).isEqualTo("PAID"); // captured right after the webhook was handled
        assertThat(paymentStatuses(o)).containsExactly("CAPTURED");
    }

    @Test
    void theCheckoutCallbackConfirmsWithAValidSignatureOnly() throws Exception {
        Drop d = liveDrop(10, 2);
        Shopper s = shopper();
        JsonNode o = order(s, d, "TEE-M", 1);
        SimulatorGateway.SignedWebhook hook = sim().pay(gatewayOrderId(o), "captured", o.path("amountMinor").asLong(), "INR", null);
        String paymentId = hook.payment().paymentId();

        mvc.perform(post("/api/orders/" + id(o) + "/confirm").header("Authorization", "Bearer " + s.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentId\":\"" + paymentId + "\",\"signature\":\"" + "f".repeat(64) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_signature"));
        mvc.perform(post("/api/orders/" + id(o) + "/confirm").header("Authorization", "Bearer " + s.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentId\":\"" + paymentId + "\",\"signature\":\"" + sim().callbackSignature(gatewayOrderId(o), paymentId) + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        // the webhook arriving afterwards finds it done
        assertThat(webhooks.handle("simulator", hook.body(), hook.headers()).outcome()).isEqualTo(PaymentService.Outcome.ALREADY_PAID);
    }

    @Test
    void whileInventoryIsRebuildingPaymentsAreRetriedLaterNotRefunded() {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopperUnchecked(), d);
        redis.opsForHash().put(Keys.meta(d.id()), "state", "REBUILDING");
        SimulatorGateway.SignedWebhook hook = sim().pay(gatewayOrderId(o), "captured", o.path("amountMinor").asLong(), "INR", null);
        try {
            assertThatThrownBy(() -> webhooks.handle("simulator", hook.body(), hook.headers()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(503));
            assertThat(orderStatus(id(o))).isEqualTo("PENDING_PAYMENT");
            assertThat(paymentStatusesOrEmpty(o)).isEmpty(); // rolled back with the dedupe row
        } finally {
            redis.opsForHash().put(Keys.meta(d.id()), "state", "READY");
        }
        // the gateway's retry goes through
        assertThat(webhooks.handle("simulator", hook.body(), hook.headers()).outcome()).isEqualTo(PaymentService.Outcome.PAID);
    }

    // helpers

    private JsonNode order(Shopper s, Drop d) {
        try {
            return order(s, d, "TEE-M", 1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Shopper shopperUnchecked() {
        return shopper();
    }

    private static String id(JsonNode o) {
        return o.path("id").asText();
    }

    private static String gatewayOrderId(JsonNode o) {
        return o.path("payment").path("gatewayOrderId").asText();
    }

    private long events(JsonNode o, String type) {
        return jdbc.sql("select count(*) from outbox where group_key = ? and type = ?").params(id(o), type).query(Long.class).single();
    }

    private java.util.List<String> paymentStatuses(JsonNode o) {
        java.util.List<String> s = paymentStatusesOrEmpty(o);
        assertThat(s).isNotEmpty();
        return s;
    }

    private java.util.List<String> paymentStatusesOrEmpty(JsonNode o) {
        return jdbc.sql("select status from payments where order_id = ? order by created_at").param(UUID.fromString(id(o)))
                .query(String.class).list();
    }

    @SuppressWarnings("unused")
    private static HttpHeaders none() {
        return new HttpHeaders();
    }
}
