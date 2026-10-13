package dev.mahindra.swiftcart.fulfilment;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import dev.mahindra.swiftcart.auth.Customer;
import dev.mahindra.swiftcart.auth.CustomerRepository;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.drop.DropRepository;
import dev.mahindra.swiftcart.order.Order;
import dev.mahindra.swiftcart.order.OrderRepository;
import dev.mahindra.swiftcart.outbox.OutboxRepository;
import dev.mahindra.swiftcart.payment.GatewayPayment;
import dev.mahindra.swiftcart.payment.Gateways;
import dev.mahindra.swiftcart.payment.PaymentRepository;

/**
 * Everything that happens after payment, off the request path: invoice, confirmation email, warehouse, refunds.
 *
 * <p>Delivery is at-least-once, so every step is idempotent: the event id is recorded with the step's effects in
 * one transaction (a redelivery is skipped), invoices are one per order, each email kind is sent once per order,
 * and the warehouse and refund calls carry idempotency keys.
 */
@Service
public class FulfilmentHandler {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentHandler.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final DropRepository drops;
    private final PaymentRepository payments;
    private final Gateways gateways;
    private final InvoiceService invoices;
    private final Notifier notifier;
    private final WarehouseClient warehouse;
    private final Clock clock;

    public FulfilmentHandler(JdbcClient jdbc, TransactionTemplate tx, OrderRepository orders, CustomerRepository customers,
            DropRepository drops, PaymentRepository payments, Gateways gateways, InvoiceService invoices, Notifier notifier,
            WarehouseClient warehouse, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.orders = orders;
        this.customers = customers;
        this.drops = drops;
        this.payments = payments;
        this.gateways = gateways;
        this.invoices = invoices;
        this.notifier = notifier;
        this.warehouse = warehouse;
        this.clock = clock;
    }

    /** Returns false for an event already handled. Throws to have the message redelivered. */
    public boolean handle(JsonNode envelope) {
        UUID eventId = UUID.fromString(envelope.path("eventId").asText());
        String type = envelope.path("type").asText();
        JsonNode payload = envelope.path("payload");
        Boolean done = tx.execute(status -> {
            int fresh = jdbc.sql("insert into processed_messages (event_id) values (?) on conflict do nothing").param(eventId).update();
            if (fresh == 0) {
                return false;
            }
            switch (type) {
                case OutboxRepository.ORDER_PAID -> orderPaid(UUID.fromString(payload.path("orderId").asText()));
                case OutboxRepository.REFUND_REQUESTED -> refund(payload);
                default -> log.warn("Ignoring unknown event type {}", type);
            }
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    private void orderPaid(UUID orderId) {
        Order order = orders.find(orderId).orElseThrow(() -> new IllegalStateException("Order " + orderId + " not found"));
        if (order.status() != Order.Status.PAID && order.status() != Order.Status.FULFILLED) {
            log.info("Order {} is {}; nothing to fulfil", orderId, order.status());
            return;
        }
        Drop drop = drops.findById(order.dropId()).orElseThrow();
        Drop.Sku sku = drop.skus().stream().filter(s -> s.id().equals(order.skuId())).findFirst().orElseThrow();
        String invoice = invoices.issue(order, clock.instant());
        Customer customer = customers.findById(order.customerId()).orElseThrow();
        once(order.id(), "order_confirmation", customer.email(), () -> notifier.send(customer.email(),
                "Your " + drop.name() + " order is confirmed",
                "Hi " + customer.name() + ",\n\nYou got it: " + order.quantity() + " x " + sku.name() + ".\n"
                        + "Invoice " + invoice + ", total " + rupees(order.amountMinor()) + ".\n"
                        + "We'll email you again when it ships.\n\nOrder " + order.id()));
        if (order.status() == Order.Status.PAID) {
            Optional<String> shipment = warehouse.ship(order, sku.code());
            shipment.ifPresent(ref -> orders.fulfilled(order.id(), ref));
        }
    }

    private void refund(JsonNode p) {
        String paymentId = p.path("paymentId").asText();
        Optional<PaymentRepository.StoredPayment> stored = payments.find(paymentId);
        if (stored.isEmpty() || "REFUNDED".equals(stored.get().status())) {
            return;
        }
        GatewayPayment payment = new GatewayPayment(p.path("gateway").asText(), paymentId,
                p.path("gatewayOrderId").asText(null), p.path("orderId").asText(null), p.path("amountMinor").asLong(),
                p.path("currency").asText("INR"), GatewayPayment.Status.CAPTURED, null);
        String refundId = gateways.get(payment.gateway()).refund(payment, p.path("reason").asText("refund"), "refund-" + paymentId);
        payments.markRefunded(paymentId, refundId);
        log.info("Refunded payment {} ({}): refund {}", paymentId, p.path("reason").asText(), refundId);
        if (stored.get().orderId() != null) {
            Order order = orders.find(stored.get().orderId()).orElse(null);
            Customer customer = order == null ? null : customers.findById(order.customerId()).orElse(null);
            if (customer != null) {
                once(order.id(), "refund_" + paymentId, customer.email(), () -> notifier.send(customer.email(),
                        "Your payment has been refunded",
                        "Hi " + customer.name() + ",\n\nWe couldn't complete order " + order.id() + " ("
                                + reasonText(p.path("reason").asText()) + "), so we've refunded "
                                + rupees(payment.amountMinor()) + ". It usually reaches your account in 5-7 working days."));
            }
        }
    }

    /** Sends an email kind at most once per order (the row is claimed first; a failed send rolls it back). */
    private void once(UUID orderId, String kind, String recipient, java.util.function.Supplier<String> send) {
        int claimed = jdbc.sql("insert into notifications (order_id, kind, recipient) values (?, ?, ?) on conflict do nothing")
                .params(orderId, kind, recipient).update();
        if (claimed == 1) {
            String providerId = send.get();
            jdbc.sql("update notifications set provider_id = ? where order_id = ? and kind = ?").params(providerId, orderId, kind).update();
        }
    }

    private static String reasonText(String reason) {
        if (reason.startsWith("reservation_expired")) {
            return "the payment arrived after your reservation ended and the item had sold out";
        }
        return switch (reason) {
            case "duplicate_payment" -> "it was paid twice";
            case "unknown_order" -> "we couldn't match the payment to an order";
            default -> reason;
        };
    }

    private static String rupees(long paise) {
        return "Rs " + (paise / 100) + "." + String.format("%02d", paise % 100);
    }
}
