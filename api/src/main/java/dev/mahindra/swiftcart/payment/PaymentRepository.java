package dev.mahindra.swiftcart.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.mahindra.swiftcart.config.Db;

@Repository
public class PaymentRepository {

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records what the gateway says about a payment. Status only moves forward: a late "authorized" or "failed"
     * event never downgrades a captured payment, and refund states are never overwritten by gateway events.
     */
    public void upsert(GatewayPayment p, UUID orderId) {
        jdbc.sql("""
                insert into payments (gateway_payment_id, gateway, order_id, gateway_order_id, amount_minor, currency, status)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (gateway_payment_id) do update set
                    order_id = coalesce(payments.order_id, excluded.order_id),
                    gateway_order_id = coalesce(payments.gateway_order_id, excluded.gateway_order_id),
                    amount_minor = excluded.amount_minor,
                    status = case
                        when payments.status in ('REFUND_PENDING', 'REFUNDED') then payments.status
                        when payments.status = 'CAPTURED' then 'CAPTURED'
                        when payments.status = 'AUTHORIZED' and excluded.status = 'FAILED' then 'AUTHORIZED'
                        else excluded.status end,
                    updated_at = now()
                """)
                .params(p.paymentId(), p.gateway(), orderId, p.gatewayOrderId(), p.amountMinor(), p.currency(), p.status().name())
                .update();
    }

    /** Claims a captured payment for refund. False if it was already being refunded (a duplicate event). */
    public boolean markRefundPending(String paymentId, String reason) {
        return jdbc.sql("""
                update payments set status = 'REFUND_PENDING', refund_reason = ?, updated_at = now()
                where gateway_payment_id = ? and status in ('CAPTURED', 'AUTHORIZED')
                """).params(reason, paymentId).update() == 1;
    }

    public boolean markRefunded(String paymentId, String refundId) {
        return jdbc.sql("""
                update payments set status = 'REFUNDED', refund_id = ?, updated_at = now()
                where gateway_payment_id = ? and status = 'REFUND_PENDING'
                """).params(refundId, paymentId).update() == 1;
    }

    public Optional<StoredPayment> find(String paymentId) {
        return jdbc.sql("select * from payments where gateway_payment_id = ?").param(paymentId)
                .query((rs, i) -> new StoredPayment(rs.getString("gateway_payment_id"), rs.getString("gateway"),
                        Db.uuid(rs, "order_id"), rs.getString("gateway_order_id"), rs.getLong("amount_minor"),
                        rs.getString("currency"), rs.getString("status"), rs.getString("refund_reason"), rs.getString("refund_id")))
                .optional();
    }

    public List<StoredPayment> forOrder(UUID orderId) {
        return jdbc.sql("select * from payments where order_id = ? order by created_at").param(orderId)
                .query((rs, i) -> new StoredPayment(rs.getString("gateway_payment_id"), rs.getString("gateway"),
                        Db.uuid(rs, "order_id"), rs.getString("gateway_order_id"), rs.getLong("amount_minor"),
                        rs.getString("currency"), rs.getString("status"), rs.getString("refund_reason"), rs.getString("refund_id")))
                .list();
    }

    /** True the first time an event is seen; the insert commits with the event's effects or not at all. */
    public boolean firstDelivery(String gateway, String eventId, String type) {
        return jdbc.sql("insert into processed_webhooks (gateway, event_id, event_type) values (?, ?, ?) on conflict do nothing")
                .params(gateway, eventId, type).update() == 1;
    }

    public record StoredPayment(String paymentId, String gateway, UUID orderId, String gatewayOrderId, long amountMinor,
            String currency, String status, String refundReason, String refundId) {

        public GatewayPayment toGateway() {
            return new GatewayPayment(gateway, paymentId, gatewayOrderId, orderId == null ? null : orderId.toString(),
                    amountMinor, currency, GatewayPayment.Status.CAPTURED, null);
        }
    }
}
