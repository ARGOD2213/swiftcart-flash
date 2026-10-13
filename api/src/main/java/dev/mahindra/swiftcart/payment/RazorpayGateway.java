package dev.mahindra.swiftcart.payment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.swiftcart.config.SwiftcartProperties;
import dev.mahindra.swiftcart.order.Order;

/**
 * Razorpay: an Orders API order per checkout, paid in Razorpay Checkout, confirmed by the checkout callback
 * signature and by webhooks. The order's receipt is our order id, so a payment can always be traced back.
 */
public class RazorpayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);

    private final SwiftcartProperties.Razorpay config;
    private final RestClient http;
    private final ObjectMapper json;

    public RazorpayGateway(SwiftcartProperties.Razorpay config, ObjectMapper json) {
        this.config = config;
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(config.keyId(), config.keySecret()))
                .build();
    }

    @Override
    public String name() {
        return "razorpay";
    }

    @Override
    public String publicKey() {
        return config.keyId();
    }

    @Override
    public Session createSession(Order order, String skuName) {
        JsonNode created = call(() -> http.post().uri("/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "amount", order.amountMinor(),
                        "currency", order.currency(),
                        "receipt", order.id().toString(),
                        "notes", Map.of("order_id", order.id().toString(), "sku", skuName)))
                .retrieve().body(JsonNode.class));
        return new Session(name(), created.path("id").asText(), config.keyId(), null);
    }

    @Override
    public List<GatewayPayment> paymentsFor(String gatewayOrderId) {
        JsonNode list = call(() -> http.get().uri("/v1/orders/{id}/payments", gatewayOrderId).retrieve().body(JsonNode.class));
        List<GatewayPayment> out = new ArrayList<>();
        list.path("items").forEach(p -> RazorpayEvents.payment(name(), p).ifPresent(out::add));
        return out;
    }

    @Override
    public Optional<GatewayPayment> fetchPayment(String paymentId) {
        try {
            JsonNode p = http.get().uri("/v1/payments/{id}", paymentId).retrieve().body(JsonNode.class);
            return RazorpayEvents.payment(name(), p);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.BadRequest e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new GatewayUnavailableException("Razorpay is unavailable", e);
        }
    }

    @Override
    public GatewayPayment capture(GatewayPayment payment) {
        try {
            JsonNode p = http.post().uri("/v1/payments/{id}/capture", payment.paymentId()).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", payment.amountMinor(), "currency", payment.currency()))
                    .retrieve().body(JsonNode.class);
            return RazorpayEvents.payment(name(), p).orElse(payment);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                // most often "already captured": read the truth instead of guessing
                return fetchPayment(payment.paymentId()).orElse(payment);
            }
            throw new GatewayUnavailableException("Razorpay capture failed", e);
        } catch (RestClientException e) {
            throw new GatewayUnavailableException("Razorpay is unavailable", e);
        }
    }

    @Override
    public String refund(GatewayPayment payment, String reason, String idempotencyKey) {
        // a refund that already exists (a retried job) is returned instead of creating a second one
        JsonNode existing = call(() -> http.get().uri("/v1/payments/{id}/refunds", payment.paymentId()).retrieve().body(JsonNode.class));
        if (existing.path("count").asInt(0) > 0) {
            String id = existing.path("items").path(0).path("id").asText();
            log.info("Payment {} already has refund {}", payment.paymentId(), id);
            return id;
        }
        String receipt = idempotencyKey.length() > 40 ? idempotencyKey.substring(0, 40) : idempotencyKey;
        JsonNode refund = call(() -> http.post().uri("/v1/payments/{id}/refund", payment.paymentId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", payment.amountMinor(), "receipt", receipt, "notes", Map.of("reason", reason)))
                .retrieve().body(JsonNode.class));
        return refund.path("id").asText();
    }

    @Override
    public WebhookEvent parseWebhook(String body, HttpHeaders headers) {
        return RazorpayEvents.parse(name(), config.webhookSecret(), body, headers, json);
    }

    /** Razorpay Checkout's handler response: HMAC of "order_id|payment_id" with the key secret. */
    @Override
    public boolean verifyClientCallback(String gatewayOrderId, String paymentId, String signature) {
        return Hmac.equal(Hmac.sha256Hex(config.keySecret(), gatewayOrderId + "|" + paymentId), signature);
    }

    private static <T> T call(Supplier<T> op) {
        try {
            T result = op.get();
            if (result == null) {
                throw new GatewayUnavailableException("Razorpay returned an empty response", null);
            }
            return result;
        } catch (RestClientException e) {
            throw new GatewayUnavailableException("Razorpay call failed: " + e.getMessage(), e);
        }
    }
}
