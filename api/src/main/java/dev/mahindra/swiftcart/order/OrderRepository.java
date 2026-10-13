package dev.mahindra.swiftcart.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.mahindra.swiftcart.config.Db;

/** Orders change status only through conditional updates ({@code where status = ...}), so races resolve in SQL. */
@Repository
public class OrderRepository {

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts unless an order for this reservation already exists (a retried checkout); returns the stored order. */
    public Order insertOrGet(Order o) {
        jdbc.sql("""
                insert into orders (id, drop_id, sku_id, customer_id, quantity, unit_price_minor, amount_minor, currency,
                                    phone, shipping_address, status, reservation_id, reserved_until, idempotency_key, gateway)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDING_PAYMENT', ?, ?, ?, ?)
                on conflict (reservation_id) do nothing
                """)
                .params(o.id(), o.dropId(), o.skuId(), o.customerId(), o.quantity(), o.unitPriceMinor(), o.amountMinor(),
                        o.currency(), o.phone(), o.shippingAddressJson(), o.reservationId(), Db.ts(o.reservedUntil()),
                        o.idempotencyKey(), o.gateway())
                .update();
        return byReservation(o.reservationId()).orElseThrow();
    }

    public Optional<Order> find(UUID id) {
        return jdbc.sql("select * from orders where id = ?").param(id).query(OrderRepository::row).optional();
    }

    /** Row lock for payment handling: one payment event changes one order at a time. */
    public Optional<Order> lock(UUID id) {
        return jdbc.sql("select * from orders where id = ? for update").param(id).query(OrderRepository::row).optional();
    }

    public Optional<Order> byReservation(UUID reservationId) {
        return jdbc.sql("select * from orders where reservation_id = ?").param(reservationId).query(OrderRepository::row).optional();
    }

    public Optional<Order> byGatewayOrder(String gateway, String gatewayOrderId) {
        return jdbc.sql("select * from orders where gateway = ? and gateway_order_id = ?").params(gateway, gatewayOrderId)
                .query(OrderRepository::row).optional();
    }

    public List<Order> forCustomer(UUID customerId, int limit) {
        return jdbc.sql("select * from orders where customer_id = ? order by created_at desc limit ?")
                .params(customerId, limit).query(OrderRepository::row).list();
    }

    public boolean setGatewayOrder(UUID id, String gatewayOrderId, String checkoutUrl) {
        return jdbc.sql("update orders set gateway_order_id = ?, checkout_url = ?, updated_at = now() where id = ? and gateway_order_id is null")
                .params(gatewayOrderId, checkoutUrl, id).update() == 1;
    }

    public boolean markPaid(UUID id, String paymentId, Instant reservedUntil) {
        return jdbc.sql("""
                update orders set status = 'PAID', paid_payment_id = ?, paid_at = now(), reserved_until = ?,
                                  last_payment_error = null, updated_at = now()
                where id = ? and status in ('PENDING_PAYMENT', 'EXPIRED', 'CANCELLED')
                """).params(paymentId, Db.ts(reservedUntil), id).update() == 1;
    }

    public boolean markReview(UUID id, String paymentId, String reason) {
        return jdbc.sql("""
                update orders set status = 'REVIEW', paid_payment_id = ?, paid_at = now(), review_reason = ?, updated_at = now()
                where id = ? and status in ('PENDING_PAYMENT', 'EXPIRED', 'CANCELLED')
                """).params(paymentId, reason, id).update() == 1;
    }

    public boolean expire(UUID reservationId) {
        return jdbc.sql("update orders set status = 'EXPIRED', updated_at = now() where reservation_id = ? and status = 'PENDING_PAYMENT'")
                .param(reservationId).update() == 1;
    }

    public boolean cancel(UUID id) {
        return jdbc.sql("update orders set status = 'CANCELLED', updated_at = now() where id = ? and status = 'PENDING_PAYMENT'")
                .param(id).update() == 1;
    }

    public void paymentFailed(UUID id, String error) {
        jdbc.sql("update orders set last_payment_error = ?, updated_at = now() where id = ? and status = 'PENDING_PAYMENT'")
                .params(error == null ? "Payment failed" : error.substring(0, Math.min(error.length(), 300)), id).update();
    }

    public boolean fulfilled(UUID id, String warehouseRef) {
        return jdbc.sql("""
                update orders set status = 'FULFILLED', warehouse_ref = ?, fulfilled_at = now(), updated_at = now()
                where id = ? and status = 'PAID'
                """).params(warehouseRef, id).update() == 1;
    }

    /** Pending orders whose hold ended a while ago: the database-side safety net for the Redis reaper. */
    public List<Order> overdue(Instant cutoff, int limit) {
        return jdbc.sql("select * from orders where status = 'PENDING_PAYMENT' and reserved_until < ? order by reserved_until limit ?")
                .params(Db.ts(cutoff), limit).query(OrderRepository::row).list();
    }

    /** Orders to ask the gateway about: pending for a while, or expired/cancelled recently (late payments). */
    public List<Order> toReconcile(Instant pendingBefore, Instant closedAfter, Instant notCheckedSince, int limit) {
        return jdbc.sql("""
                select * from orders
                where gateway_order_id is not null
                  and (reconciled_at is null or reconciled_at < ?)
                  and ((status = 'PENDING_PAYMENT' and created_at < ?)
                    or (status in ('EXPIRED', 'CANCELLED') and updated_at > ?))
                order by created_at
                limit ?
                """).params(Db.ts(notCheckedSince), Db.ts(pendingBefore), Db.ts(closedAfter), limit)
                .query(OrderRepository::row).list();
    }

    public void reconciled(UUID id) {
        jdbc.sql("update orders set reconciled_at = now() where id = ?").param(id).update();
    }

    /** Units still held, per SKU, by orders: the order log Redis is rebuilt from. */
    public List<Holding> holdings(UUID dropId, Instant now) {
        return jdbc.sql("""
                select id, sku_id, customer_id, phone, quantity, status, reservation_id, reserved_until
                from orders
                where drop_id = ?
                  and (status in ('PAID', 'FULFILLED', 'REVIEW') or (status = 'PENDING_PAYMENT' and reserved_until > ?))
                """).params(dropId, Db.ts(now))
                .query((rs, i) -> new Holding(Db.uuid(rs, "id"), Db.uuid(rs, "sku_id"), Db.uuid(rs, "customer_id"),
                        rs.getString("phone"), rs.getInt("quantity"), Order.Status.valueOf(rs.getString("status")),
                        Db.uuid(rs, "reservation_id"), Db.instant(rs, "reserved_until")))
                .list();
    }

    public record Holding(UUID orderId, UUID skuId, UUID customerId, String phone, int quantity, Order.Status status,
            UUID reservationId, Instant reservedUntil) {
    }

    static Order row(ResultSet rs, int i) throws SQLException {
        return new Order(Db.uuid(rs, "id"), Db.uuid(rs, "drop_id"), Db.uuid(rs, "sku_id"), Db.uuid(rs, "customer_id"),
                rs.getInt("quantity"), rs.getLong("unit_price_minor"), rs.getLong("amount_minor"), rs.getString("currency"),
                rs.getString("phone"), rs.getString("shipping_address"), Order.Status.valueOf(rs.getString("status")),
                Db.uuid(rs, "reservation_id"), Db.instant(rs, "reserved_until"), rs.getString("idempotency_key"),
                rs.getString("gateway"), rs.getString("gateway_order_id"), rs.getString("checkout_url"), rs.getString("paid_payment_id"),
                Db.instant(rs, "paid_at"), rs.getString("last_payment_error"), rs.getString("review_reason"),
                rs.getString("warehouse_ref"), Db.instant(rs, "fulfilled_at"), Db.instant(rs, "created_at"));
    }
}
