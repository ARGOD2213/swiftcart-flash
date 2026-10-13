package dev.mahindra.swiftcart.payment;

/**
 * A payment as the gateway reports it, in gateway-neutral terms.
 *
 * @param gatewayOrderId the gateway's id for our order (Razorpay order, Stripe Checkout Session); may be null
 * @param orderRef our own order id when the gateway carries it (metadata / client reference); may be null
 */
public record GatewayPayment(String gateway, String paymentId, String gatewayOrderId, String orderRef, long amountMinor,
        String currency, Status status, String error) {

    public enum Status {
        AUTHORIZED, CAPTURED, FAILED
    }
}
