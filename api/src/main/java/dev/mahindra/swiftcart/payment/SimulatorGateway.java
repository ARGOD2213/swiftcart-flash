package dev.mahindra.swiftcart.payment;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.HttpHeaders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.swiftcart.config.SwiftcartProperties;
import dev.mahindra.swiftcart.order.Order;

/**
 * A local stand-in for Razorpay, for development, demos and load tests: same webhook format, same signature
 * scheme, same code path through webhooks, reconciliation and refunds. Payments are kept in memory, so it is
 * for a single API instance and never enabled in production (see {@code SwiftcartProperties.Payments}).
 */
public class SimulatorGateway implements PaymentGateway {

    private final SwiftcartProperties.Simulator config;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<String, List<GatewayPayment>> payments = new ConcurrentHashMap<>();

    public SimulatorGateway(SwiftcartProperties.Simulator config, ObjectMapper json, Clock clock) {
        this.config = config;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "simulator";
    }

    @Override
    public String publicKey() {
        return "sim_key";
    }

    @Override
    public Session createSession(Order order, String skuName) {
        String id = "sim_order_" + order.id().toString().replace("-", "");
        payments.putIfAbsent(id, new CopyOnWriteArrayList<>());
        return new Session(name(), id, "sim_key", null);
    }

    @Override
    public List<GatewayPayment> paymentsFor(String gatewayOrderId) {
        return List.copyOf(payments.getOrDefault(gatewayOrderId, List.of()));
    }

    @Override
    public Optional<GatewayPayment> fetchPayment(String paymentId) {
        return payments.values().stream().flatMap(List::stream).filter(p -> p.paymentId().equals(paymentId)).reduce((a, b) -> b);
    }

    @Override
    public GatewayPayment capture(GatewayPayment payment) {
        GatewayPayment captured = new GatewayPayment(name(), payment.paymentId(), payment.gatewayOrderId(), payment.orderRef(),
                payment.amountMinor(), payment.currency(), GatewayPayment.Status.CAPTURED, null);
        record(captured);
        return captured;
    }

    @Override
    public String refund(GatewayPayment payment, String reason, String idempotencyKey) {
        return "sim_rfnd_" + payment.paymentId().replace("sim_pay_", "");
    }

    @Override
    public WebhookEvent parseWebhook(String body, HttpHeaders headers) {
        return RazorpayEvents.parse(name(), config.webhookSecret(), body, headers, json);
    }

    @Override
    public boolean verifyClientCallback(String gatewayOrderId, String paymentId, String signature) {
        return Hmac.equal(Hmac.sha256Hex(config.webhookSecret(), gatewayOrderId + "|" + paymentId), signature);
    }

    /**
     * Simulates the shopper paying (or failing to). Returns the webhook Razorpay would send, already signed.
     * {@code amountMinor} lets tests send a mismatched amount.
     */
    public SignedWebhook pay(String gatewayOrderId, String status, long amountMinor, String currency, String paymentId) {
        String id = paymentId != null ? paymentId : "sim_pay_" + UUID.randomUUID().toString().replace("-", "");
        GatewayPayment.Status s = switch (status) {
            case "captured" -> GatewayPayment.Status.CAPTURED;
            case "authorized" -> GatewayPayment.Status.AUTHORIZED;
            default -> GatewayPayment.Status.FAILED;
        };
        GatewayPayment payment = new GatewayPayment(name(), id, gatewayOrderId, null, amountMinor, currency, s,
                s == GatewayPayment.Status.FAILED ? "Payment declined by the bank (simulated)" : null);
        record(payment);
        return webhook(payment, "evt_sim_" + UUID.randomUUID().toString().replace("-", ""));
    }

    /** The webhook for an already-recorded payment, e.g. to deliver it a second time or late. */
    public SignedWebhook webhook(GatewayPayment p, String eventId) {
        String event = switch (p.status()) {
            case CAPTURED -> "payment.captured";
            case AUTHORIZED -> "payment.authorized";
            case FAILED -> "payment.failed";
        };
        Map<String, Object> entity = new java.util.LinkedHashMap<>();
        entity.put("id", p.paymentId());
        entity.put("entity", "payment");
        entity.put("amount", p.amountMinor());
        entity.put("currency", p.currency());
        entity.put("status", p.status().name().toLowerCase());
        entity.put("order_id", p.gatewayOrderId());
        entity.put("method", "upi");
        entity.put("error_description", p.error());
        Map<String, Object> body = Map.of(
                "entity", "event",
                "event", event,
                "contains", List.of("payment"),
                "payload", Map.of("payment", Map.of("entity", entity)),
                "created_at", clock.instant().getEpochSecond());
        String raw;
        try {
            raw = json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(RazorpayEvents.SIGNATURE_HEADER, Hmac.sha256Hex(config.webhookSecret(), raw));
        headers.set(RazorpayEvents.EVENT_ID_HEADER, eventId);
        return new SignedWebhook(raw, headers, p);
    }

    /** The checkout-callback signature a real Razorpay Checkout would hand the browser. */
    public String callbackSignature(String gatewayOrderId, String paymentId) {
        return Hmac.sha256Hex(config.webhookSecret(), gatewayOrderId + "|" + paymentId);
    }

    private void record(GatewayPayment p) {
        List<GatewayPayment> list = payments.computeIfAbsent(p.gatewayOrderId(), k -> new CopyOnWriteArrayList<>());
        list.removeIf(existing -> existing.paymentId().equals(p.paymentId()));
        list.add(p);
    }

    public record SignedWebhook(String body, HttpHeaders headers, GatewayPayment payment) {
    }
}
