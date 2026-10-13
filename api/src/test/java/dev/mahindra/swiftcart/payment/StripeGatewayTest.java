package dev.mahindra.swiftcart.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpHeaders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import dev.mahindra.swiftcart.config.SwiftcartProperties;
import dev.mahindra.swiftcart.order.Order;

/** The Stripe adapter against a stubbed Stripe API. */
class StripeGatewayTest {

    @RegisterExtension
    static WireMockExtension stripe = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final Instant NOW = Instant.parse("2026-10-13T06:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private StripeGateway gateway() {
        return new StripeGateway(new SwiftcartProperties.Stripe(stripe.baseUrl(), "sk_test_x", "whsec_test",
                "https://shop.example/orders/{ORDER_ID}", "https://shop.example/orders/{ORDER_ID}?cancelled=1", Duration.ofMinutes(5)),
                new ObjectMapper(), clock);
    }

    @Test
    void aCheckoutSessionIsCreatedOncePerOrderWithAnIdempotencyKey() {
        Order order = RazorpayGatewayTest.order();
        stripe.stubFor(WireMock.post(urlEqualTo("/v1/checkout/sessions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"cs_test_1\",\"url\":\"https://checkout.stripe.com/c/pay/cs_test_1\"}")));

        PaymentGateway.Session s = gateway().createSession(order, "Tee M");

        assertThat(s.gatewayOrderId()).isEqualTo("cs_test_1");
        assertThat(s.checkoutUrl()).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_1");
        stripe.verify(postRequestedFor(urlEqualTo("/v1/checkout/sessions"))
                .withHeader("Idempotency-Key", equalTo("checkout-session-" + order.id()))
                .withHeader("Authorization", equalTo("Bearer sk_test_x"))
                .withRequestBody(containing("client_reference_id=" + order.id()))
                .withRequestBody(containing("line_items%5B0%5D%5Bprice_data%5D%5Bunit_amount%5D=149900"))
                .withRequestBody(containing("line_items%5B0%5D%5Bquantity%5D=2"))
                // Stripe's minimum session lifetime
                .withRequestBody(containing("expires_at=" + NOW.plus(Duration.ofMinutes(31)).getEpochSecond())));
    }

    @Test
    void aPaidSessionWebhookBecomesACapturedPayment() {
        String body = "{\"id\":\"evt_1\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"cs_test_1\","
                + "\"payment_status\":\"paid\",\"payment_intent\":\"pi_1\",\"amount_total\":299800,\"currency\":\"inr\","
                + "\"client_reference_id\":\"6f1c1c1e-0000-0000-0000-000000000001\"}}}";
        PaymentGateway.WebhookEvent e = gateway().parseWebhook(body, signed(body, NOW.getEpochSecond()));
        assertThat(e.eventId()).isEqualTo("evt_1");
        assertThat(e.payment()).hasValueSatisfying(p -> {
            assertThat(p.paymentId()).isEqualTo("pi_1");
            assertThat(p.gatewayOrderId()).isEqualTo("cs_test_1");
            assertThat(p.currency()).isEqualTo("INR");
            assertThat(p.amountMinor()).isEqualTo(299800);
            assertThat(p.status()).isEqualTo(GatewayPayment.Status.CAPTURED);
        });
    }

    @Test
    void anUnpaidCompletedSessionIsNotAPaymentYet() {
        String body = "{\"id\":\"evt_2\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"cs_2\","
                + "\"payment_status\":\"unpaid\",\"payment_intent\":\"pi_2\",\"amount_total\":100,\"currency\":\"inr\"}}}";
        assertThat(gateway().parseWebhook(body, signed(body, NOW.getEpochSecond())).payment()).isEmpty();
    }

    @Test
    void staleForgedOrMalformedSignaturesAreRejected() {
        String body = "{\"id\":\"evt_3\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{}}}";
        assertThatThrownBy(() -> gateway().parseWebhook(body, signed(body, NOW.minus(Duration.ofMinutes(6)).getEpochSecond())))
                .isInstanceOf(PaymentGateway.InvalidSignatureException.class);
        HttpHeaders forged = new HttpHeaders();
        forged.set("Stripe-Signature", "t=" + NOW.getEpochSecond() + ",v1=" + "0".repeat(64));
        assertThatThrownBy(() -> gateway().parseWebhook(body, forged)).isInstanceOf(PaymentGateway.InvalidSignatureException.class);
        HttpHeaders malformed = new HttpHeaders();
        malformed.set("Stripe-Signature", "garbage");
        assertThatThrownBy(() -> gateway().parseWebhook(body, malformed)).isInstanceOf(PaymentGateway.InvalidSignatureException.class);
        assertThatThrownBy(() -> gateway().parseWebhook(body, new HttpHeaders())).isInstanceOf(PaymentGateway.InvalidSignatureException.class);
    }

    @Test
    void aValidSignatureAmongSeveralIsAccepted() {
        String body = "{\"id\":\"evt_4\",\"type\":\"customer.created\",\"data\":{\"object\":{}}}";
        long t = NOW.getEpochSecond();
        HttpHeaders h = new HttpHeaders();
        h.set("Stripe-Signature", "t=" + t + ",v1=" + "a".repeat(64) + ",v1=" + Hmac.sha256Hex("whsec_test", t + "." + body));
        assertThat(gateway().parseWebhook(body, h).payment()).isEmpty();
    }

    @Test
    void refundsCarryTheIdempotencyKey() {
        stripe.stubFor(WireMock.post(urlEqualTo("/v1/refunds")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"re_1\"}")));
        GatewayPayment p = new GatewayPayment("stripe", "pi_1", "cs_1", null, 299800, "INR", GatewayPayment.Status.CAPTURED, null);
        assertThat(gateway().refund(p, "duplicate_payment", "refund-pi_1")).isEqualTo("re_1");
        stripe.verify(postRequestedFor(urlEqualTo("/v1/refunds")).withHeader("Idempotency-Key", equalTo("refund-pi_1"))
                .withRequestBody(containing("payment_intent=pi_1")));
    }

    @Test
    void theReconcilerSeesAPaidSession() {
        stripe.stubFor(get(urlEqualTo("/v1/checkout/sessions/cs_9")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"cs_9\",\"status\":\"complete\",\"payment_status\":\"paid\",\"payment_intent\":\"pi_9\","
                        + "\"amount_total\":500,\"currency\":\"inr\"}")));
        assertThat(gateway().paymentsFor("cs_9")).singleElement()
                .satisfies(p -> assertThat(p.status()).isEqualTo(GatewayPayment.Status.CAPTURED));
    }

    private static HttpHeaders signed(String body, long t) {
        HttpHeaders h = new HttpHeaders();
        h.set("Stripe-Signature", "t=" + t + ",v1=" + Hmac.sha256Hex("whsec_test", t + "." + body));
        return h;
    }
}
