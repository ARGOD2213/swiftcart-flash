package dev.mahindra.swiftcart.payment;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpHeaders;

import dev.mahindra.swiftcart.order.Order;

/** What checkout needs from a payment provider. Razorpay, Stripe and a local simulator implement it. */
public interface PaymentGateway {

    String name();

    /** The key the browser uses to open the provider's checkout (Razorpay key id); null if none. */
    default String publicKey() {
        return null;
    }

    /**
     * Creates the provider-side payment object for an order (Razorpay order, Stripe Checkout Session). Must be
     * safe to call twice for the same order.
     */
    Session createSession(Order order, String skuName);

    /** Every payment the provider has for this gateway order: how the reconciler catches missed webhooks. */
    List<GatewayPayment> paymentsFor(String gatewayOrderId);

    Optional<GatewayPayment> fetchPayment(String paymentId);

    /** Captures an authorized payment (Razorpay without auto-capture). No-op where capture is automatic. */
    GatewayPayment capture(GatewayPayment payment);

    /** Refunds in full. {@code idempotencyKey} makes a retried refund return the first one. Returns the refund id. */
    String refund(GatewayPayment payment, String reason, String idempotencyKey);

    /** Verifies the signature and parses the event. Throws {@link InvalidSignatureException} if forged or stale. */
    WebhookEvent parseWebhook(String body, HttpHeaders headers);

    /** Razorpay's checkout callback signature; false where the provider has none. */
    default boolean verifyClientCallback(String gatewayOrderId, String paymentId, String signature) {
        return false;
    }

    /** What the storefront needs to start paying. */
    record Session(String gateway, String gatewayOrderId, String publicKey, String checkoutUrl) {
    }

    /** {@code payment} is empty for events that don't concern a payment we act on. */
    record WebhookEvent(String eventId, String type, Optional<GatewayPayment> payment) {
    }

    class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) {
            super(message);
        }
    }

    /** The provider failed or timed out; the caller may retry later. */
    class GatewayUnavailableException extends RuntimeException {
        public GatewayUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
