package dev.mahindra.swiftcart.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import dev.mahindra.swiftcart.config.SwiftcartProperties;
import dev.mahindra.swiftcart.order.Order;
import dev.mahindra.swiftcart.order.OrderRepository;
import dev.mahindra.swiftcart.payment.GatewayPayment;
import dev.mahindra.swiftcart.payment.Gateways;
import dev.mahindra.swiftcart.payment.PaymentGateway;
import dev.mahindra.swiftcart.payment.PaymentService;
import dev.mahindra.swiftcart.payment.WebhookService;

/**
 * Webhooks get lost, arrive late, or fail while the database is down. The reconciler asks the gateway directly
 * about orders that have been pending for a while and orders that expired or were cancelled recently, and runs
 * whatever it finds through the same {@link PaymentService} rules: complete, rescue, refund or capture.
 */
@Component
@ConditionalOnProperty(name = "swiftcart.jobs.enabled", havingValue = "true")
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);
    private static final Duration RECHECK_AFTER = Duration.ofMinutes(1);

    private final OrderRepository orders;
    private final Gateways gateways;
    private final PaymentService payments;
    private final WebhookService webhooks;
    private final Locks locks;
    private final TransactionTemplate tx;
    private final SwiftcartProperties.Payments config;
    private final Clock clock;

    public PaymentReconciler(OrderRepository orders, Gateways gateways, PaymentService payments, WebhookService webhooks,
            Locks locks, TransactionTemplate tx, SwiftcartProperties props, Clock clock) {
        this.orders = orders;
        this.gateways = gateways;
        this.payments = payments;
        this.webhooks = webhooks;
        this.locks = locks;
        this.tx = tx;
        this.config = props.payments();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${swiftcart.jobs.reconcile-interval-ms:30000}", initialDelay = 10000)
    public void scheduled() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.warn("Reconciliation round failed: {}", e.getMessage());
        }
    }

    /** Returns how many gateway payments were applied. */
    public int reconcile() {
        Instant now = clock.instant();
        // claim a batch under the leader lock (stamping reconciled_at), then talk to the gateway without it
        List<Order> batch = locks.asLeader("reconciler", () -> {
            List<Order> due = orders.toReconcile(now.minus(config.staleAfter()), now.minus(config.lateWindow()),
                    now.minus(RECHECK_AFTER), 50);
            due.forEach(o -> orders.reconciled(o.id()));
            return due;
        }, List.of());
        int applied = 0;
        for (Order o : batch) {
            PaymentGateway gateway;
            try {
                gateway = gateways.get(o.gateway());
            } catch (RuntimeException e) {
                continue;
            }
            try {
                for (GatewayPayment p : gateway.paymentsFor(o.gatewayOrderId())) {
                    PaymentService.Outcome outcome = tx.execute(s -> payments.apply(p));
                    if (outcome == PaymentService.Outcome.CAPTURE_NEEDED) {
                        webhooks.capture(gateway, p);
                    }
                    if (outcome != PaymentService.Outcome.ALREADY_PAID && outcome != PaymentService.Outcome.IGNORED) {
                        applied++;
                        log.info("Reconciled order {} from {}: payment {} {} -> {}", o.id(), gateway.name(), p.paymentId(),
                                p.status(), outcome);
                    }
                }
            } catch (PaymentGateway.GatewayUnavailableException e) {
                log.warn("Gateway unavailable while reconciling order {}: {}", o.id(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Could not reconcile order {}: {}", o.id(), e.getMessage());
            }
        }
        return applied;
    }
}
