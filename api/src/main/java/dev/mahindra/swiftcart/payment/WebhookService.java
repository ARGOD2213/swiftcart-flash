package dev.mahindra.swiftcart.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Webhook handling: verify, de-duplicate and apply in one transaction. If applying fails (database down,
 * inventory being rebuilt) the de-duplication row rolls back with it and the gateway's retry is processed.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final Gateways gateways;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final TransactionTemplate tx;

    public WebhookService(Gateways gateways, PaymentRepository payments, PaymentService paymentService, TransactionTemplate tx) {
        this.gateways = gateways;
        this.payments = payments;
        this.paymentService = paymentService;
        this.tx = tx;
    }

    public Result handle(String gatewayName, String body, HttpHeaders headers) {
        PaymentGateway gateway = gateways.get(gatewayName);
        PaymentGateway.WebhookEvent event = gateway.parseWebhook(body, headers);
        if (event.payment().isEmpty()) {
            return new Result(event.eventId(), "ignored", null);
        }
        Result result = tx.execute(status -> {
            if (!payments.firstDelivery(gatewayName, event.eventId(), event.type())) {
                return new Result(event.eventId(), "duplicate", null);
            }
            PaymentService.Outcome outcome = paymentService.apply(event.payment().get());
            return new Result(event.eventId(), "processed", outcome);
        });
        if (result != null && result.outcome() == PaymentService.Outcome.CAPTURE_NEEDED) {
            capture(gateway, event.payment().get());
        }
        log.info("{} webhook {} ({}): {} {}", gatewayName, event.eventId(), event.type(),
                result == null ? "?" : result.status(), result == null || result.outcome() == null ? "" : result.outcome());
        return result;
    }

    /**
     * Captures outside the transaction (no HTTP call while holding row locks). The captured payment is applied
     * right away; the gateway's own "captured" webhook later finds it already done. If capture fails, the
     * reconciler retries it.
     */
    public void capture(PaymentGateway gateway, GatewayPayment authorized) {
        try {
            GatewayPayment captured = gateway.capture(authorized);
            if (captured.status() == GatewayPayment.Status.CAPTURED) {
                tx.executeWithoutResult(s -> paymentService.apply(captured));
            }
        } catch (PaymentGateway.GatewayUnavailableException e) {
            log.warn("Capture of {} failed, the reconciler will retry: {}", authorized.paymentId(), e.getMessage());
        }
    }

    public record Result(String eventId, String status, PaymentService.Outcome outcome) {
    }
}
