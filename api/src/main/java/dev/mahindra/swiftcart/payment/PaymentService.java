package dev.mahindra.swiftcart.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.drop.DropCatalog;
import dev.mahindra.swiftcart.inventory.InventoryService;
import dev.mahindra.swiftcart.order.Order;
import dev.mahindra.swiftcart.order.OrderRepository;
import dev.mahindra.swiftcart.outbox.OutboxRepository;

/**
 * Turns what a gateway says about a payment into order state. Every path (webhook, checkout callback,
 * reconciler) comes through {@link #apply}, inside the caller's transaction, with the order row locked, so two
 * deliveries of the same news can't both act on it.
 *
 * <p>The rules that make "never oversell, charge exactly once" hold:
 * <ul>
 *   <li>A captured payment for a held reservation confirms it; the order becomes PAID.</li>
 *   <li>A captured payment that arrives after the hold was released tries to take the units again
 *       (late-pay rescue). If they're gone, the payment is refunded; stock is never conjured up.</li>
 *   <li>A second captured payment for an already-paid order is refunded.</li>
 *   <li>A payment for an amount or currency that doesn't match goes to REVIEW: units held, nothing shipped.</li>
 *   <li>A payment for no known order is refunded.</li>
 * </ul>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final OutboxRepository outbox;
    private final InventoryService inventory;
    private final DropCatalog drops;
    private final Clock clock;

    public PaymentService(OrderRepository orders, PaymentRepository payments, OutboxRepository outbox,
            InventoryService inventory, DropCatalog drops, Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.outbox = outbox;
        this.inventory = inventory;
        this.drops = drops;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome apply(GatewayPayment p) {
        Optional<Order> found = resolve(p).flatMap(o -> orders.lock(o.id()));
        payments.upsert(p, found.map(Order::id).orElse(null));
        if (found.isEmpty()) {
            if (p.status() == GatewayPayment.Status.CAPTURED) {
                log.warn("Captured payment {} matches no order; refunding", p.paymentId());
                return refund(p, null, "unknown_order");
            }
            return Outcome.IGNORED;
        }
        Order order = found.get();
        return switch (p.status()) {
            case FAILED -> {
                orders.paymentFailed(order.id(), p.error());
                yield Outcome.FAILURE_RECORDED;
            }
            case AUTHORIZED -> shouldCapture(order) ? Outcome.CAPTURE_NEEDED : Outcome.IGNORED;
            case CAPTURED -> captured(order, p);
        };
    }

    private Outcome captured(Order order, GatewayPayment p) {
        if (p.paymentId().equals(order.paidPaymentId())) {
            return Outcome.ALREADY_PAID;
        }
        if (order.status().paid()) {
            log.warn("Order {} was already paid by {}; refunding second payment {}", order.id(), order.paidPaymentId(), p.paymentId());
            return refund(p, order.id(), "duplicate_payment");
        }
        Drop drop = drops.byId(order.dropId());
        if (order.status() == Order.Status.PENDING_PAYMENT) {
            InventoryService.ConfirmResult confirmed = inventory.confirm(drop.id(), order.reservationId());
            switch (confirmed) {
                case CONFIRMED, ALREADY -> {
                    return settle(order, p, order.reservedUntil(), Outcome.PAID);
                }
                case NOT_READY -> throw retryLater();
                case MISSING -> {
                    // the hold was released (expired, or the safety net freed it) a moment before the payment landed
                }
            }
        }
        return rescue(order, drop, p);
    }

    /** The payment arrived after the hold ended: take the units again if they're still there, else refund. */
    private Outcome rescue(Order order, Drop drop, GatewayPayment p) {
        String sku = skuCode(drop, order);
        InventoryService.ReserveResult again = inventory.reserve(drop.id(), sku, order.quantity(), order.customerId(),
                order.phone(), order.reservationId());
        if (again.code() == InventoryService.ReserveResult.Code.NOT_READY) {
            throw retryLater();
        }
        if (again.held()) {
            InventoryService.ConfirmResult confirmed = inventory.confirm(drop.id(), order.reservationId());
            if (confirmed == InventoryService.ConfirmResult.NOT_READY) {
                throw retryLater();
            }
            log.info("Late payment {} rescued order {}: units were still available", p.paymentId(), order.id());
            Instant until = again.expiresAt() != null ? again.expiresAt() : clock.instant();
            return settle(order, p, until, Outcome.RESCUED);
        }
        if (order.status() == Order.Status.PENDING_PAYMENT) {
            orders.expire(order.reservationId());
        }
        log.info("Late payment {} for order {} can't be honoured ({}); refunding", p.paymentId(), order.id(), again.code());
        return refund(p, order.id(), "reservation_expired_" + again.code().name().toLowerCase());
    }

    private Outcome settle(Order order, GatewayPayment p, Instant reservedUntil, Outcome success) {
        boolean amountOk = p.amountMinor() == order.amountMinor() && order.currency().equalsIgnoreCase(p.currency());
        if (!amountOk) {
            String reason = "amount_mismatch: paid " + p.amountMinor() + " " + p.currency() + ", expected "
                    + order.amountMinor() + " " + order.currency();
            log.error("Order {}: {}", order.id(), reason);
            orders.markReview(order.id(), p.paymentId(), reason);
            return Outcome.REVIEW;
        }
        if (!orders.markPaid(order.id(), p.paymentId(), reservedUntil)) {
            // the row is locked and its status was checked above, so this means a bug; fail loudly and retry
            throw new IllegalStateException("Order " + order.id() + " could not be marked paid from " + order.status());
        }
        Map<String, Object> event = new HashMap<>();
        event.put("orderId", order.id().toString());
        event.put("paymentId", p.paymentId());
        event.put("amountMinor", order.amountMinor());
        event.put("currency", order.currency());
        outbox.add(order.id().toString(), OutboxRepository.ORDER_PAID, event);
        return success;
    }

    private Outcome refund(GatewayPayment p, UUID orderId, String reason) {
        if (payments.markRefundPending(p.paymentId(), reason)) {
            Map<String, Object> event = new HashMap<>();
            event.put("paymentId", p.paymentId());
            event.put("gateway", p.gateway());
            event.put("gatewayOrderId", p.gatewayOrderId());
            event.put("orderId", orderId == null ? null : orderId.toString());
            event.put("amountMinor", p.amountMinor());
            event.put("currency", p.currency());
            event.put("reason", reason);
            outbox.add(orderId != null ? orderId.toString() : p.paymentId(), OutboxRepository.REFUND_REQUESTED, event);
        }
        return Outcome.REFUNDING;
    }

    /** Don't capture money for units we can't ship: an uncaptured authorization lapses without a refund. */
    private boolean shouldCapture(Order order) {
        if (order.status() == Order.Status.PENDING_PAYMENT) {
            return true;
        }
        if (order.status() == Order.Status.EXPIRED || order.status() == Order.Status.CANCELLED) {
            Drop drop = drops.byId(order.dropId());
            Long left = inventory.available(drop.id()).get(skuCode(drop, order));
            return left != null && left >= order.quantity() && drop.phase(clock.instant()) == Drop.Phase.LIVE;
        }
        return false;
    }

    private Optional<Order> resolve(GatewayPayment p) {
        if (p.gatewayOrderId() != null) {
            Optional<Order> o = orders.byGatewayOrder(p.gateway(), p.gatewayOrderId());
            if (o.isPresent()) {
                return o;
            }
        }
        if (p.orderRef() != null) {
            try {
                return orders.find(UUID.fromString(p.orderRef()));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static String skuCode(Drop drop, Order order) {
        return drop.skus().stream().filter(s -> s.id().equals(order.skuId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Order " + order.id() + " refers to an unknown SKU")).code();
    }

    private static ApiException retryLater() {
        return ApiException.unavailable("inventory_rebuilding", "Inventory is being rebuilt; deliver this again shortly");
    }

    public enum Outcome {
        PAID, RESCUED, ALREADY_PAID, REVIEW, REFUNDING, FAILURE_RECORDED, CAPTURE_NEEDED, IGNORED
    }
}
