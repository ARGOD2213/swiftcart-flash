package dev.mahindra.swiftcart.payment;

import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpHeaders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Razorpay webhook format: signature over the raw body, event id in a header, payment entity in the payload. */
final class RazorpayEvents {

    static final String SIGNATURE_HEADER = "X-Razorpay-Signature";
    static final String EVENT_ID_HEADER = "x-razorpay-event-id";
    private static final Set<String> PAYMENT_EVENTS = Set.of("payment.authorized", "payment.captured", "payment.failed", "order.paid");

    private RazorpayEvents() {
    }

    static PaymentGateway.WebhookEvent parse(String gateway, String secret, String body, HttpHeaders headers, ObjectMapper json) {
        String signature = headers.getFirst(SIGNATURE_HEADER);
        if (!Hmac.equal(Hmac.sha256Hex(secret, body), signature)) {
            throw new PaymentGateway.InvalidSignatureException("Webhook signature does not match");
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JsonProcessingException e) {
            throw new PaymentGateway.InvalidSignatureException("Webhook body is not JSON");
        }
        String type = root.path("event").asText("");
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String eventId = headers.getFirst(EVENT_ID_HEADER);
        if (eventId == null || eventId.isBlank()) {
            // older deliveries without the header: the same event always hashes the same
            eventId = "derived:" + Hmac.sha256Hex("event-id", type + "|" + payment.path("id").asText() + "|"
                    + payment.path("status").asText() + "|" + root.path("created_at").asText());
        }
        if (!PAYMENT_EVENTS.contains(type) || payment.isMissingNode()) {
            return new PaymentGateway.WebhookEvent(eventId, type, Optional.empty());
        }
        return new PaymentGateway.WebhookEvent(eventId, type, payment(gateway, payment));
    }

    static Optional<GatewayPayment> payment(String gateway, JsonNode p) {
        GatewayPayment.Status status = switch (p.path("status").asText("")) {
            case "captured" -> GatewayPayment.Status.CAPTURED;
            case "authorized" -> GatewayPayment.Status.AUTHORIZED;
            case "failed" -> GatewayPayment.Status.FAILED;
            default -> null;
        };
        if (status == null || p.path("id").asText("").isBlank()) {
            return Optional.empty();
        }
        String error = p.path("error_description").isTextual() ? p.path("error_description").asText() : null;
        String orderRef = p.path("notes").path("order_id").isTextual() ? p.path("notes").path("order_id").asText() : null;
        return Optional.of(new GatewayPayment(gateway, p.path("id").asText(), textOrNull(p.path("order_id")), orderRef,
                p.path("amount").asLong(), p.path("currency").asText("INR").toUpperCase(), status, error));
    }

    private static String textOrNull(JsonNode n) {
        return n.isTextual() && !n.asText().isBlank() ? n.asText() : null;
    }
}
