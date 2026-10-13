package dev.mahindra.swiftcart.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpHeaders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import dev.mahindra.swiftcart.config.SwiftcartProperties;
import dev.mahindra.swiftcart.order.Order;

/** The Razorpay adapter against a stubbed Razorpay API. */
class RazorpayGatewayTest {

    @RegisterExtension
    static WireMockExtension razorpay = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final ObjectMapper json = new ObjectMapper();

    private RazorpayGateway gateway() {
        return new RazorpayGateway(new SwiftcartProperties.Razorpay(razorpay.baseUrl(), "rzp_test_key", "key_secret", "whsec"), json);
    }

    @Test
    void createsAnOrderWithOurOrderIdAsReceipt() {
        Order order = order();
        razorpay.stubFor(WireMock.post(urlEqualTo("/v1/orders")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"order_ABC\",\"amount\":299800,\"currency\":\"INR\",\"status\":\"created\"}")));

        PaymentGateway.Session s = gateway().createSession(order, "Tee M");

        assertThat(s.gatewayOrderId()).isEqualTo("order_ABC");
        assertThat(s.publicKey()).isEqualTo("rzp_test_key");
        razorpay.verify(postRequestedFor(urlEqualTo("/v1/orders"))
                .withHeader("Authorization", equalTo("Basic " + Base64.getEncoder().encodeToString("rzp_test_key:key_secret".getBytes())))
                .withRequestBody(equalToJson("{\"amount\":299800,\"currency\":\"INR\",\"receipt\":\"" + order.id() + "\"}", true, true)));
    }

    @Test
    void anOutageBecomesGatewayUnavailable() {
        razorpay.stubFor(WireMock.post(urlEqualTo("/v1/orders")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> gateway().createSession(order(), "Tee"))
                .isInstanceOf(PaymentGateway.GatewayUnavailableException.class);
    }

    @Test
    void webhooksAreVerifiedOverTheRawBody() {
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\",\"amount\":299800,"
                + "\"currency\":\"INR\",\"status\":\"captured\",\"order_id\":\"order_ABC\"}}},\"created_at\":1700000000}";
        HttpHeaders h = new HttpHeaders();
        h.set("X-Razorpay-Signature", Hmac.sha256Hex("whsec", body));
        h.set("x-razorpay-event-id", "evt_1");

        PaymentGateway.WebhookEvent e = gateway().parseWebhook(body, h);
        assertThat(e.eventId()).isEqualTo("evt_1");
        assertThat(e.payment()).hasValueSatisfying(p -> {
            assertThat(p.paymentId()).isEqualTo("pay_1");
            assertThat(p.gatewayOrderId()).isEqualTo("order_ABC");
            assertThat(p.amountMinor()).isEqualTo(299800);
            assertThat(p.status()).isEqualTo(GatewayPayment.Status.CAPTURED);
        });

        h.set("X-Razorpay-Signature", Hmac.sha256Hex("wrong-secret", body));
        assertThatThrownBy(() -> gateway().parseWebhook(body, h)).isInstanceOf(PaymentGateway.InvalidSignatureException.class);
        h.remove("X-Razorpay-Signature");
        assertThatThrownBy(() -> gateway().parseWebhook(body, h)).isInstanceOf(PaymentGateway.InvalidSignatureException.class);
    }

    @Test
    void eventsWeDontActOnAreAcknowledgedWithoutAPayment() {
        String body = "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_1\"}}}}";
        HttpHeaders h = new HttpHeaders();
        h.set("X-Razorpay-Signature", Hmac.sha256Hex("whsec", body));
        assertThat(gateway().parseWebhook(body, h).payment()).isEmpty();
    }

    @Test
    void theCheckoutCallbackSignatureIsOrderIdPipePaymentId() {
        String sig = Hmac.sha256Hex("key_secret", "order_ABC|pay_1");
        assertThat(gateway().verifyClientCallback("order_ABC", "pay_1", sig)).isTrue();
        assertThat(gateway().verifyClientCallback("order_ABC", "pay_2", sig)).isFalse();
        assertThat(gateway().verifyClientCallback("order_ABC", "pay_1", null)).isFalse();
    }

    @Test
    void aRefundThatAlreadyExistsIsReturnedNotRepeated() {
        razorpay.stubFor(get(urlEqualTo("/v1/payments/pay_1/refunds")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"count\":1,\"items\":[{\"id\":\"rfnd_existing\"}]}")));
        String id = gateway().refund(payment(), "duplicate_payment", "refund-pay_1");
        assertThat(id).isEqualTo("rfnd_existing");
        razorpay.verify(0, postRequestedFor(urlEqualTo("/v1/payments/pay_1/refund")));
    }

    @Test
    void aNewRefundCarriesTheIdempotencyKeyAsReceipt() {
        razorpay.stubFor(get(urlEqualTo("/v1/payments/pay_1/refunds")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"count\":0,\"items\":[]}")));
        razorpay.stubFor(WireMock.post(urlEqualTo("/v1/payments/pay_1/refund")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"rfnd_new\"}")));
        assertThat(gateway().refund(payment(), "unknown_order", "refund-pay_1")).isEqualTo("rfnd_new");
        razorpay.verify(postRequestedFor(urlEqualTo("/v1/payments/pay_1/refund"))
                .withRequestBody(equalToJson("{\"amount\":299800,\"receipt\":\"refund-pay_1\"}", true, true)));
    }

    @Test
    void captureOfAnAlreadyCapturedPaymentReadsTheTruth() {
        razorpay.stubFor(WireMock.post(urlEqualTo("/v1/payments/pay_1/capture")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"This payment has already been captured\"}}")));
        razorpay.stubFor(get(urlEqualTo("/v1/payments/pay_1")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"pay_1\",\"amount\":299800,\"currency\":\"INR\",\"status\":\"captured\",\"order_id\":\"order_ABC\"}")));
        GatewayPayment authorized = new GatewayPayment("razorpay", "pay_1", "order_ABC", null, 299800, "INR",
                GatewayPayment.Status.AUTHORIZED, null);
        assertThat(gateway().capture(authorized).status()).isEqualTo(GatewayPayment.Status.CAPTURED);
    }

    @Test
    void paymentsForAnOrderMapEveryStatus() {
        razorpay.stubFor(get(urlEqualTo("/v1/orders/order_ABC/payments")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"count\":3,\"items\":["
                        + "{\"id\":\"pay_f\",\"amount\":299800,\"currency\":\"INR\",\"status\":\"failed\",\"order_id\":\"order_ABC\",\"error_description\":\"declined\"},"
                        + "{\"id\":\"pay_a\",\"amount\":299800,\"currency\":\"INR\",\"status\":\"authorized\",\"order_id\":\"order_ABC\"},"
                        + "{\"id\":\"pay_c\",\"amount\":299800,\"currency\":\"INR\",\"status\":\"captured\",\"order_id\":\"order_ABC\"}]}")));
        assertThat(gateway().paymentsFor("order_ABC")).extracting(GatewayPayment::status).containsExactly(
                GatewayPayment.Status.FAILED, GatewayPayment.Status.AUTHORIZED, GatewayPayment.Status.CAPTURED);
    }

    private static GatewayPayment payment() {
        return new GatewayPayment("razorpay", "pay_1", "order_ABC", null, 299800, "INR", GatewayPayment.Status.CAPTURED, null);
    }

    static Order order() {
        return new Order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2, 149_900, 299_800, "INR",
                "+919876543210", "{}", Order.Status.PENDING_PAYMENT, UUID.randomUUID(), Instant.now().plusSeconds(600), "key",
                "razorpay", null, null, null, null, null, null, null, null, Instant.now());
    }
}
