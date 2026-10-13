package dev.mahindra.swiftcart.payment;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /webhooks/razorpay|stripe|simulator}. The raw body is kept as a string: signatures are computed
 * over the exact bytes, so it must not be parsed and re-serialised before verification.
 *
 * <p>Responses: 200 for processed, duplicate and ignored events (so the gateway stops retrying); 400 for a bad
 * signature; 503 when the event couldn't be applied yet (the gateway retries).
 */
@RestController
class WebhookController {

    private final WebhookService webhooks;

    WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/webhooks/{gateway}")
    ResponseEntity<Map<String, Object>> receive(@PathVariable String gateway, @RequestBody(required = false) String body,
            @RequestHeader HttpHeaders headers) {
        if (body == null || body.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", "empty_body"));
        }
        try {
            WebhookService.Result r = webhooks.handle(gateway, body, headers);
            return ResponseEntity.ok(Map.of("eventId", r.eventId(), "status", r.status(),
                    "outcome", r.outcome() == null ? "none" : r.outcome().name()));
        } catch (PaymentGateway.InvalidSignatureException e) {
            return ResponseEntity.badRequest().body(Map.of("code", "invalid_signature", "detail", e.getMessage()));
        }
    }
}
