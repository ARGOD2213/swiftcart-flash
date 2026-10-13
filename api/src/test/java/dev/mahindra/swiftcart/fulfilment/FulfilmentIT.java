package dev.mahindra.swiftcart.fulfilment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.WireMock;

import dev.mahindra.swiftcart.IntegrationTest;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.outbox.OutboxRelay;

/** Outbox → SQS FIFO → worker → invoice, email, warehouse, refunds. */
class FulfilmentIT extends IntegrationTest {

    @Autowired
    OutboxRelay relay;
    @Autowired
    FulfilmentHandler handler;

    @BeforeEach
    void warehouse() {
        WAREHOUSE.stubFor(WireMock.post(urlEqualTo("/shipments"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"id\":\"SHP-123\"}")));
    }

    @Test
    void aPaidOrderIsInvoicedEmailedAndShippedOnce() throws Exception {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopper(), d, "TEE-M", 2);
        pay(o);
        UUID id = UUID.fromString(o.path("id").asText());

        await().atMost(Duration.ofSeconds(60)).until(() -> drained() && "FULFILLED".equals(orderStatus(id.toString())));

        String invoice = jdbc.sql("select number from invoices where order_id = ?").param(id).query(String.class).single();
        assertThat(invoice).matches("SC/\\d{4}-\\d{2}/\\d{6}");
        assertThat(jdbc.sql("select count(*) from notifications where order_id = ? and kind = 'order_confirmation'").param(id)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select warehouse_ref from orders where id = ?").param(id).query(String.class).single()).isEqualTo("SHP-123");
        WAREHOUSE.verify(postRequestedFor(urlEqualTo("/shipments")).withHeader("Idempotency-Key", equalTo("order-" + id)));
        assertThat(jdbc.sql("select count(*) from outbox where group_key = ? and published_at is null").param(id.toString())
                .query(Long.class).single()).isZero();
    }

    @Test
    void aRedeliveredEventIsHandledOnce() throws Exception {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopper(), d, "TEE-M", 1);
        pay(o);
        String event = jdbc.sql("select event_id::text from outbox where group_key = ? and type = 'ORDER_PAID'")
                .param(o.path("id").asText()).query(String.class).single();
        // handled here directly, as if SQS delivered it, then delivered again
        jdbc.sql("update outbox set published_at = now() where event_id = ?::uuid").param(event).update();
        JsonNode envelope = json.readTree("{\"eventId\":\"" + event + "\",\"type\":\"ORDER_PAID\",\"payload\":{\"orderId\":\""
                + o.path("id").asText() + "\"}}");
        assertThat(handler.handle(envelope)).isTrue();
        assertThat(handler.handle(envelope)).isFalse();
        assertThat(jdbc.sql("select count(*) from invoices where order_id = ?").param(UUID.fromString(o.path("id").asText()))
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void invoiceNumbersAreGaplessAndOnePerOrder() throws Exception {
        Drop d = liveDrop(10, 2);
        JsonNode a = order(shopper(), d, "TEE-M", 1);
        JsonNode b = order(shopper(), d, "TEE-M", 1);
        pay(a);
        pay(b);
        await().atMost(Duration.ofSeconds(60)).until(() -> drained() && "FULFILLED".equals(orderStatus(a.path("id").asText()))
                && "FULFILLED".equals(orderStatus(b.path("id").asText())));
        assertThat(number(a)).isNotEqualTo(number(b));
        // across everything invoiced so far: numbers 1..N with no holes, one invoice per order
        long count = jdbc.sql("select count(*) from invoices").query(Long.class).single();
        long max = jdbc.sql("select max(cast(substring(number from '[0-9]+$') as integer)) from invoices").query(Long.class).single();
        long orders = jdbc.sql("select count(distinct order_id) from invoices").query(Long.class).single();
        assertThat(max).isEqualTo(count);
        assertThat(orders).isEqualTo(count);
    }

    @Test
    void aDuplicatePaymentIsRefundedThroughTheGatewayAndTheShopperIsTold() throws Exception {
        Drop d = liveDrop(10, 2);
        JsonNode o = order(shopper(), d, "TEE-M", 1);
        pay(o);
        String second = pay(o).eventId();
        assertThat(second).isNotBlank();
        await().atMost(Duration.ofSeconds(60)).until(() -> drained() && jdbc.sql(
                "select count(*) from payments where order_id = ? and status = 'REFUNDED'")
                .param(UUID.fromString(o.path("id").asText())).query(Long.class).single() == 1);
        assertThat(jdbc.sql("select refund_id from payments where order_id = ? and status = 'REFUNDED'")
                .param(UUID.fromString(o.path("id").asText())).query(String.class).single()).startsWith("sim_rfnd_");
        assertThat(jdbc.sql("select count(*) from notifications where order_id = ? and kind like 'refund_%'")
                .param(UUID.fromString(o.path("id").asText())).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void financialYearsRunAprilToMarch() {
        assertThat(InvoiceService.financialYear(java.time.LocalDate.of(2026, 3, 31))).isEqualTo("2025-26");
        assertThat(InvoiceService.financialYear(java.time.LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
        assertThat(InvoiceService.financialYear(java.time.LocalDate.of(2099, 12, 31))).isEqualTo("2099-00");
    }

    /** Publishes everything waiting in the outbox (earlier tests leave events behind too). */
    private boolean drained() {
        relay.relay();
        return jdbc.sql("select count(*) from outbox where published_at is null and next_attempt_at <= now()")
                .query(Long.class).single() == 0;
    }

    private int number(JsonNode o) {
        String n = jdbc.sql("select number from invoices where order_id = ?").param(UUID.fromString(o.path("id").asText()))
                .query(String.class).single();
        return Integer.parseInt(n.substring(n.lastIndexOf('/') + 1));
    }
}
