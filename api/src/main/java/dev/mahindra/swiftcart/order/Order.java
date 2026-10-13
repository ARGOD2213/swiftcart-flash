package dev.mahindra.swiftcart.order;

import java.time.Instant;
import java.util.UUID;

public record Order(UUID id, UUID dropId, UUID skuId, UUID customerId, int quantity, long unitPriceMinor,
        long amountMinor, String currency, String phone, String shippingAddressJson, Status status, UUID reservationId,
        Instant reservedUntil, String idempotencyKey, String gateway, String gatewayOrderId, String checkoutUrl, String paidPaymentId,
        Instant paidAt, String lastPaymentError, String reviewReason, String warehouseRef, Instant fulfilledAt,
        Instant createdAt) {

    public enum Status {
        PENDING_PAYMENT, PAID, FULFILLED, EXPIRED, CANCELLED, REVIEW;

        /** Statuses that keep units out of stock. */
        public boolean holdsStock() {
            return this == PENDING_PAYMENT || this == PAID || this == FULFILLED || this == REVIEW;
        }

        public boolean paid() {
            return this == PAID || this == FULFILLED || this == REVIEW;
        }
    }
}
