package dev.mahindra.swiftcart.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.auth.CurrentCustomer;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.drop.DropCatalog;
import dev.mahindra.swiftcart.inventory.InventoryService;
import dev.mahindra.swiftcart.payment.GatewayPayment;
import dev.mahindra.swiftcart.payment.Gateways;
import dev.mahindra.swiftcart.payment.PaymentGateway;
import dev.mahindra.swiftcart.payment.PaymentRepository;
import dev.mahindra.swiftcart.payment.PaymentService;
import dev.mahindra.swiftcart.payment.WebhookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/orders")
class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final DropCatalog drops;
    private final PaymentSessions sessions;
    private final InventoryService inventory;
    private final Gateways gateways;
    private final PaymentService paymentService;
    private final WebhookService webhooks;
    private final TransactionTemplate tx;

    OrderController(OrderRepository orders, PaymentRepository payments, DropCatalog drops, PaymentSessions sessions,
            InventoryService inventory, Gateways gateways, PaymentService paymentService, WebhookService webhooks,
            TransactionTemplate tx) {
        this.orders = orders;
        this.payments = payments;
        this.drops = drops;
        this.sessions = sessions;
        this.inventory = inventory;
        this.gateways = gateways;
        this.paymentService = paymentService;
        this.webhooks = webhooks;
        this.tx = tx;
    }

    @GetMapping
    List<OrderView> mine(JwtAuthenticationToken auth) {
        return orders.forCustomer(CurrentCustomer.id(auth), 50).stream().map(o -> view(o, false)).toList();
    }

    @GetMapping("/{id}")
    OrderView one(@PathVariable UUID id, JwtAuthenticationToken auth) {
        return view(owned(id, auth), true);
    }

    /** Creates the gateway payment if checkout couldn't (gateway was down). Safe to call repeatedly. */
    @PostMapping("/{id}/payment-session")
    OrderView paymentSession(@PathVariable UUID id, JwtAuthenticationToken auth) {
        Order o = owned(id, auth);
        if (o.status() != Order.Status.PENDING_PAYMENT) {
            throw ApiException.conflict("order_not_payable", "This order is " + o.status().name().toLowerCase())
                    .with("status", o.status().name());
        }
        Drop drop = drops.byId(o.dropId());
        Optional<PaymentGateway.Session> session = sessions.ensure(o, drop);
        if (session.isEmpty()) {
            throw ApiException.unavailable("payment_unavailable", "The payment provider is not responding; your items stay reserved");
        }
        return view(orders.find(id).orElseThrow(), true);
    }

    /** Gives the units back. Idempotent; a paid order can't be cancelled here. */
    @PostMapping("/{id}/cancel")
    OrderView cancel(@PathVariable UUID id, JwtAuthenticationToken auth) {
        Order o = owned(id, auth);
        if (o.status().paid()) {
            throw ApiException.conflict("already_paid", "This order is paid; contact support to cancel it");
        }
        if (o.status() == Order.Status.PENDING_PAYMENT) {
            InventoryService.ReleaseResult r = inventory.release(o.dropId(), o.reservationId(), false);
            if (r == InventoryService.ReleaseResult.NOT_READY) {
                throw ApiException.unavailable("inventory_unavailable", "Try again in a moment");
            }
            if (r == InventoryService.ReleaseResult.CONFIRMED) {
                // a payment confirmed it a moment ago
                throw ApiException.conflict("already_paid", "This order was just paid");
            }
            orders.cancel(id);
        }
        return view(orders.find(id).orElseThrow(), false);
    }

    /**
     * Razorpay Checkout's success callback. The signature proves the payment belongs to this order; the amount
     * and status come from the gateway itself, never from the browser. If the gateway can't be reached, the
     * webhook will finish the job, so the shopper sees "confirming" rather than an error.
     */
    @PostMapping("/{id}/confirm")
    ResponseEntity<OrderView> confirm(@PathVariable UUID id, @Valid @RequestBody Confirm body, JwtAuthenticationToken auth) {
        Order o = owned(id, auth);
        if (o.gatewayOrderId() == null) {
            throw ApiException.conflict("no_payment", "This order has no payment to confirm");
        }
        PaymentGateway gateway = gateways.get(o.gateway());
        if (!gateway.verifyClientCallback(o.gatewayOrderId(), body.paymentId(), body.signature())) {
            throw ApiException.badRequest("invalid_signature", "The payment signature doesn't match this order");
        }
        Optional<GatewayPayment> payment;
        try {
            payment = gateway.fetchPayment(body.paymentId());
        } catch (PaymentGateway.GatewayUnavailableException e) {
            log.warn("Gateway unreachable while confirming order {}; waiting for the webhook", id);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(o, true));
        }
        if (payment.isEmpty()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(o, true));
        }
        PaymentService.Outcome outcome = tx.execute(s -> paymentService.apply(payment.get()));
        if (outcome == PaymentService.Outcome.CAPTURE_NEEDED) {
            webhooks.capture(gateway, payment.get());
        }
        return ResponseEntity.ok(view(orders.find(id).orElseThrow(), true));
    }

    private Order owned(UUID id, JwtAuthenticationToken auth) {
        UUID customer = CurrentCustomer.id(auth);
        // someone else's order looks exactly like a missing one
        return orders.find(id).filter(o -> o.customerId().equals(customer))
                .orElseThrow(() -> ApiException.notFound("order_not_found", "No such order"));
    }

    private OrderView view(Order o, boolean withSession) {
        Drop drop = drops.byId(o.dropId());
        PaymentGateway.Session session = null;
        if (withSession && o.status() == Order.Status.PENDING_PAYMENT && o.gatewayOrderId() != null) {
            PaymentGateway g = gateways.get(o.gateway());
            session = new PaymentGateway.Session(g.name(), o.gatewayOrderId(), g.publicKey(), o.checkoutUrl());
        }
        return OrderView.of(o, drop, session, null, payments.forOrder(o.id()));
    }

    record Confirm(@NotBlank @Size(max = 100) String paymentId, @NotBlank @Size(max = 200) String signature) {
    }
}
