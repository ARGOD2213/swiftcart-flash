package dev.mahindra.swiftcart.order;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.payment.Gateways;
import dev.mahindra.swiftcart.payment.PaymentGateway;

/**
 * Creates the gateway-side payment object for an order exactly once. The order row is locked while the gateway
 * is called, so two concurrent "pay" clicks can't create two Razorpay orders (Stripe additionally gets an
 * idempotency key). If the gateway is down, the order keeps its reservation and the shopper can retry.
 */
@Service
public class PaymentSessions {

    private static final Logger log = LoggerFactory.getLogger(PaymentSessions.class);

    private final OrderRepository orders;
    private final Gateways gateways;
    private final TransactionTemplate tx;

    public PaymentSessions(OrderRepository orders, Gateways gateways, TransactionTemplate tx) {
        this.orders = orders;
        this.gateways = gateways;
        this.tx = tx;
    }

    /** Empty if the gateway couldn't be reached; the order is still reserved and payable later. */
    public Optional<PaymentGateway.Session> ensure(Order order, Drop drop) {
        PaymentGateway gateway = gateways.get(order.gateway());
        if (order.gatewayOrderId() != null) {
            return Optional.of(existing(gateway, order));
        }
        try {
            return Optional.ofNullable(tx.execute(status -> {
                Order locked = orders.lock(order.id()).orElseThrow();
                if (locked.gatewayOrderId() != null) {
                    return existing(gateway, locked);
                }
                if (locked.status() != Order.Status.PENDING_PAYMENT) {
                    return null;
                }
                String skuName = drop.skus().stream().filter(s -> s.id().equals(order.skuId())).findFirst()
                        .map(Drop.Sku::name).orElse("Item");
                PaymentGateway.Session session = gateway.createSession(locked, skuName);
                orders.setGatewayOrder(locked.id(), session.gatewayOrderId(), session.checkoutUrl());
                return session;
            }));
        } catch (PaymentGateway.GatewayUnavailableException e) {
            log.warn("Could not create a {} payment for order {}: {}", gateway.name(), order.id(), e.getMessage());
            return Optional.empty();
        }
    }

    private static PaymentGateway.Session existing(PaymentGateway gateway, Order order) {
        return new PaymentGateway.Session(gateway.name(), order.gatewayOrderId(), gateway.publicKey(), order.checkoutUrl());
    }
}
