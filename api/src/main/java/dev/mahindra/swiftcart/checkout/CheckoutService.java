package dev.mahindra.swiftcart.checkout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.inventory.InventoryService;
import dev.mahindra.swiftcart.inventory.InventoryService.ReserveResult;
import dev.mahindra.swiftcart.order.Order;
import dev.mahindra.swiftcart.order.OrderRepository;
import dev.mahindra.swiftcart.order.OrderView;
import dev.mahindra.swiftcart.order.PaymentSessions;
import dev.mahindra.swiftcart.payment.Gateways;
import dev.mahindra.swiftcart.payment.PaymentGateway;

/**
 * One checkout: reserve units atomically in Redis, write the order, open a payment with the gateway.
 *
 * <p>The reservation id is derived from (customer, idempotency key), so a retry after a crash finds the same
 * reservation instead of taking a second one. If the order write fails, the units are released at once; if the
 * process dies before that, the reservation simply expires.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final InventoryService inventory;
    private final OrderRepository orders;
    private final PaymentSessions sessions;
    private final Gateways gateways;
    private final ObjectMapper json;

    public CheckoutService(InventoryService inventory, OrderRepository orders, PaymentSessions sessions, Gateways gateways,
            ObjectMapper json) {
        this.inventory = inventory;
        this.orders = orders;
        this.sessions = sessions;
        this.gateways = gateways;
        this.json = json;
    }

    public OrderView checkout(Drop drop, UUID customer, String idempotencyKey, CheckoutRequest req) {
        Drop.Sku sku = drop.sku(req.sku())
                .orElseThrow(() -> ApiException.badRequest("unknown_sku", "This drop has no SKU " + req.sku()));
        if (req.quantity() > drop.perCustomerLimit()) {
            throw ApiException.badRequest("quantity_over_limit", "At most " + drop.perCustomerLimit() + " per customer")
                    .with("limit", drop.perCustomerLimit());
        }
        String phone = Phones.normalizeIndianMobile(req.phone())
                .orElseThrow(() -> ApiException.badRequest("invalid_phone", "Enter a 10-digit Indian mobile number"));
        long amount;
        try {
            amount = Math.multiplyExact(sku.priceMinor(), (long) req.quantity());
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("amount_too_large", "That order is too large");
        }

        UUID reservationId = reservationId(customer, idempotencyKey);
        ReserveResult r = inventory.reserve(drop.id(), sku.code(), req.quantity(), customer, phone, reservationId);
        refuseUnlessHeld(r, drop);

        Order order;
        try {
            order = orders.insertOrGet(new Order(UUID.randomUUID(), drop.id(), sku.id(), customer, req.quantity(),
                    sku.priceMinor(), amount, sku.currency(), phone, address(req.address()), Order.Status.PENDING_PAYMENT,
                    reservationId, r.expiresAt(), idempotencyKey, gateways.active().name(), null, null, null, null, null,
                    null, null, null, null));
        } catch (RuntimeException e) {
            // never strand units: give them back now rather than in ten minutes
            log.error("Order write failed after reserving {} (reservation {}); releasing", sku.code(), reservationId, e);
            try {
                inventory.release(drop.id(), reservationId, false);
            } catch (RuntimeException releaseFailed) {
                log.warn("Release failed too; the reservation will expire on its own", releaseFailed);
            }
            throw ApiException.unavailable("order_unavailable", "We couldn't save your order; please try again");
        }

        Optional<PaymentGateway.Session> session = sessions.ensure(order, drop);
        return OrderView.of(order, drop, session.orElse(null), session.isEmpty() ? "payment_unavailable" : null, List.of());
    }

    /** Deterministic per (customer, key): a retried request maps to the same reservation. */
    static UUID reservationId(UUID customer, String idempotencyKey) {
        return UUID.nameUUIDFromBytes((customer + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
    }

    private static void refuseUnlessHeld(ReserveResult r, Drop drop) {
        switch (r.code()) {
            case OK, DUPLICATE -> {
            }
            case NOT_READY -> throw ApiException.unavailable("inventory_unavailable", "The drop is getting ready; retry in a moment");
            case NOT_OPEN -> throw ApiException.conflict("drop_not_open", "This drop hasn't opened yet").with("opensAt", drop.opensAt());
            case CLOSED -> throw ApiException.conflict("drop_closed", "This drop has ended");
            case SOLD_OUT -> throw ApiException.conflict("sold_out", "Sold out");
            case INSUFFICIENT -> throw ApiException.conflict("insufficient_stock", "Only " + r.value() + " left")
                    .with("available", r.value());
            case LIMIT -> throw ApiException.conflict("limit_reached",
                    "You can buy " + drop.perCustomerLimit() + " per drop; you can add " + r.value() + " more")
                    .with("limit", drop.perCustomerLimit()).with("remainingAllowance", r.value());
            case PHONE_LIMIT -> throw ApiException.conflict("phone_limit_reached",
                    "This phone number has reached the limit for this drop").with("remainingAllowance", r.value());
            case UNKNOWN_SKU -> throw ApiException.badRequest("unknown_sku", "This drop has no such SKU");
            case BAD_QTY -> throw ApiException.badRequest("quantity_over_limit", "At most " + r.value() + " per customer")
                    .with("limit", r.value());
            default -> throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected", "Unexpected inventory answer");
        }
    }

    private String address(CheckoutRequest.Address a) {
        try {
            return json.writeValueAsString(a);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
