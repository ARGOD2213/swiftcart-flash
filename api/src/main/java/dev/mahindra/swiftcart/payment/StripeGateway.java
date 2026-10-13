package dev.mahindra.swiftcart.payment;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.swiftcart.config.SwiftcartProperties;
import dev.mahindra.swiftcart.order.Order;

/**
 * Stripe: a Checkout Session per order, created with {@code Idempotency-Key} = the order id, so a retried or
 * concurrent create returns the same session instead of a second one. Paid sessions arrive as webhooks.
 */
public class StripeGateway implements PaymentGateway {

    private final SwiftcartProperties.Stripe config;
    private final RestClient http;
    private final ObjectMapper json;
    private final Clock clock;

    public StripeGateway(SwiftcartProperties.Stripe config, ObjectMapper json, Clock clock) {
        this.config = config;
        this.json = json;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(factory)
                .defaultHeaders(h -> h.setBearerAuth(config.secretKey()))
                .build();
    }

    @Override
    public String name() {
        return "stripe";
    }

    @Override
    public Session createSession(Order order, String skuName) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        form.add("client_reference_id", order.id().toString());
        form.add("metadata[order_id]", order.id().toString());
        form.add("payment_intent_data[metadata][order_id]", order.id().toString());
        form.add("line_items[0][quantity]", String.valueOf(order.quantity()));
        form.add("line_items[0][price_data][currency]", order.currency().toLowerCase(Locale.ROOT));
        form.add("line_items[0][price_data][unit_amount]", String.valueOf(order.unitPriceMinor()));
        form.add("line_items[0][price_data][product_data][name]", skuName);
        form.add("success_url", config.successUrl().replace("{ORDER_ID}", order.id().toString()));
        form.add("cancel_url", config.cancelUrl().replace("{ORDER_ID}", order.id().toString()));
        // Stripe's minimum session lifetime is 30 minutes; a payment after our hold ends is rescued or refunded
        form.add("expires_at", String.valueOf(clock.instant().plus(Duration.ofMinutes(31)).getEpochSecond()));
        JsonNode session = call(() -> http.post().uri("/v1/checkout/sessions")
                .header("Idempotency-Key", "checkout-session-" + order.id())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(JsonNode.class));
        return new Session(name(), session.path("id").asText(), null, session.path("url").asText(null));
    }

    @Override
    public List<GatewayPayment> paymentsFor(String sessionId) {
        JsonNode s = call(() -> http.get().uri("/v1/checkout/sessions/{id}", sessionId).retrieve().body(JsonNode.class));
        List<GatewayPayment> out = new ArrayList<>();
        sessionPayment(s, "paid".equals(s.path("payment_status").asText()) ? GatewayPayment.Status.CAPTURED : null)
                .ifPresent(out::add);
        return out;
    }

    @Override
    public Optional<GatewayPayment> fetchPayment(String paymentIntentId) {
        try {
            JsonNode pi = http.get().uri("/v1/payment_intents/{id}", paymentIntentId).retrieve().body(JsonNode.class);
            return pi == null ? Optional.empty() : intentPayment(pi);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new GatewayUnavailableException("Stripe is unavailable", e);
        }
    }

    @Override
    public GatewayPayment capture(GatewayPayment payment) {
        return payment; // Checkout Sessions capture automatically
    }

    @Override
    public String refund(GatewayPayment payment, String reason, String idempotencyKey) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("payment_intent", payment.paymentId());
        form.add("metadata[reason]", reason);
        JsonNode refund = call(() -> http.post().uri("/v1/refunds")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(JsonNode.class));
        return refund.path("id").asText();
    }

    @Override
    public WebhookEvent parseWebhook(String body, HttpHeaders headers) {
        verify(body, headers.getFirst("Stripe-Signature"));
        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (JsonProcessingException e) {
            throw new InvalidSignatureException("Webhook body is not JSON");
        }
        String type = event.path("type").asText("");
        JsonNode object = event.path("data").path("object");
        Optional<GatewayPayment> payment = switch (type) {
            case "checkout.session.completed" -> sessionPayment(object,
                    "paid".equals(object.path("payment_status").asText()) ? GatewayPayment.Status.CAPTURED : null);
            case "checkout.session.async_payment_succeeded" -> sessionPayment(object, GatewayPayment.Status.CAPTURED);
            case "checkout.session.async_payment_failed" -> sessionPayment(object, GatewayPayment.Status.FAILED);
            case "payment_intent.payment_failed" -> intentPayment(object);
            default -> Optional.empty();
        };
        return new WebhookEvent(event.path("id").asText(), type, payment);
    }

    /** {@code t=<unix>,v1=<hex>}: HMAC-SHA256 of "t.body", within the tolerance window (replay protection). */
    private void verify(String body, String header) {
        if (header == null || header.isBlank()) {
            throw new InvalidSignatureException("Missing Stripe-Signature header");
        }
        String timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("t")) {
                timestamp = kv[1];
            } else if (kv.length == 2 && kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new InvalidSignatureException("Malformed Stripe-Signature header");
        }
        long age;
        try {
            age = Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp));
        } catch (NumberFormatException e) {
            throw new InvalidSignatureException("Malformed Stripe-Signature timestamp");
        }
        if (age > config.tolerance().toSeconds()) {
            throw new InvalidSignatureException("Stripe-Signature timestamp outside the tolerance window");
        }
        String expected = Hmac.sha256Hex(config.webhookSecret(), timestamp + "." + body);
        if (signatures.stream().noneMatch(s -> Hmac.equal(expected, s))) {
            throw new InvalidSignatureException("Webhook signature does not match");
        }
    }

    private Optional<GatewayPayment> sessionPayment(JsonNode s, GatewayPayment.Status status) {
        String intent = s.path("payment_intent").isTextual() ? s.path("payment_intent").asText()
                : s.path("payment_intent").path("id").asText(null);
        if (status == null || intent == null) {
            return Optional.empty();
        }
        String orderRef = s.path("client_reference_id").asText(s.path("metadata").path("order_id").asText(null));
        return Optional.of(new GatewayPayment(name(), intent, s.path("id").asText(null), orderRef,
                s.path("amount_total").asLong(), s.path("currency").asText("inr").toUpperCase(Locale.ROOT), status, null));
    }

    private Optional<GatewayPayment> intentPayment(JsonNode pi) {
        String stripeStatus = pi.path("status").asText("");
        String error = pi.path("last_payment_error").path("message").asText(null);
        GatewayPayment.Status status = switch (stripeStatus) {
            case "succeeded" -> GatewayPayment.Status.CAPTURED;
            case "requires_capture" -> GatewayPayment.Status.AUTHORIZED;
            case "canceled" -> GatewayPayment.Status.FAILED;
            case "requires_payment_method" -> error != null ? GatewayPayment.Status.FAILED : null;
            default -> null;
        };
        if (status == null) {
            return Optional.empty();
        }
        long amount = status == GatewayPayment.Status.CAPTURED && pi.path("amount_received").asLong() > 0
                ? pi.path("amount_received").asLong() : pi.path("amount").asLong();
        return Optional.of(new GatewayPayment(name(), pi.path("id").asText(), null,
                pi.path("metadata").path("order_id").asText(null), amount,
                pi.path("currency").asText("inr").toUpperCase(Locale.ROOT), status, error));
    }

    private static <T> T call(Supplier<T> op) {
        try {
            T result = op.get();
            if (result == null) {
                throw new GatewayUnavailableException("Stripe returned an empty response", null);
            }
            return result;
        } catch (RestClientException e) {
            throw new GatewayUnavailableException("Stripe call failed: " + e.getMessage(), e);
        }
    }
}
