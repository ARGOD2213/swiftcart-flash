package dev.mahindra.swiftcart.order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.payment.PaymentGateway;
import dev.mahindra.swiftcart.payment.PaymentRepository;

/** What a shopper sees of an order. {@code payment} is how to pay it while it's payable. */
public record OrderView(UUID id, String status, String drop, String sku, String skuName, int quantity,
        long unitPriceMinor, long amountMinor, String currency, Instant reservedUntil, Instant createdAt, Instant paidAt,
        String lastPaymentError, Payment payment, String paymentError, List<PaymentLine> payments) {

    public static OrderView of(Order o, Drop drop, PaymentGateway.Session session, String paymentError,
            List<PaymentRepository.StoredPayment> stored) {
        Drop.Sku sku = drop.skus().stream().filter(s -> s.id().equals(o.skuId())).findFirst().orElse(null);
        Payment payment = session == null || o.status() != Order.Status.PENDING_PAYMENT ? null
                : new Payment(session.gateway(), session.gatewayOrderId(), session.publicKey(), session.checkoutUrl(),
                        o.amountMinor(), o.currency());
        return new OrderView(o.id(), o.status().name(), drop.slug(), sku == null ? null : sku.code(),
                sku == null ? null : sku.name(), o.quantity(), o.unitPriceMinor(), o.amountMinor(), o.currency(),
                o.reservedUntil(), o.createdAt(), o.paidAt(), o.lastPaymentError(), payment, paymentError,
                stored.stream().map(p -> new PaymentLine(p.paymentId(), p.status(), p.amountMinor(), p.refundReason())).toList());
    }

    public record Payment(String gateway, String gatewayOrderId, String keyId, String checkoutUrl, long amountMinor,
            String currency) {
    }

    public record PaymentLine(String id, String status, long amountMinor, String refundReason) {
    }
}
