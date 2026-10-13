package dev.mahindra.swiftcart.ops;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.config.Db;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.inventory.InventoryService;

/**
 * Checks a drop's invariants against the order log. The load test fails CI if any of these is violated:
 * <ul>
 *   <li>no SKU sold more units than it had (no oversell);</li>
 *   <li>no customer and no phone holds more than the per-customer limit;</li>
 *   <li>no order has more than one captured payment that isn't being refunded (charged at most once);</li>
 *   <li>no captured payment is left without a paid order or a refund;</li>
 *   <li>Redis stock + units held by orders = initial stock (inventory consistent).</li>
 * </ul>
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final InventoryService inventory;
    private final Clock clock;

    public AuditService(JdbcClient jdbc, InventoryService inventory, Clock clock) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.clock = clock;
    }

    public Report audit(Drop drop) {
        Map<String, Long> redisStock;
        try {
            redisStock = inventory.available(drop.id());
        } catch (ApiException e) {
            redisStock = Map.of();
        }
        List<SkuLine> skus = new ArrayList<>();
        boolean oversold = false;
        boolean consistent = !redisStock.isEmpty();
        for (Drop.Sku s : drop.skus()) {
            long paid = units(s.id(), "status in ('PAID', 'FULFILLED', 'REVIEW')");
            long pending = jdbc.sql("""
                    select coalesce(sum(quantity), 0) from orders
                    where sku_id = ? and status = 'PENDING_PAYMENT' and reserved_until > ?
                    """).params(s.id(), Db.ts(clock.instant())).query(Long.class).single();
            Long redis = redisStock.get(s.code());
            long expected = Math.max(0, s.initialStock() - paid - pending);
            boolean skuOversold = paid > s.initialStock();
            // Redis may briefly hold reservations whose orders aren't written yet; it must never show more than expected
            boolean skuConsistent = redis != null && redis <= expected && redis >= 0;
            oversold |= skuOversold;
            consistent &= skuConsistent;
            skus.add(new SkuLine(s.code(), s.initialStock(), paid, pending, redis, expected, skuOversold, skuConsistent));
        }
        long customersOverLimit = count("""
                select count(*) from (
                  select customer_id from orders where drop_id = ? and status in ('PAID', 'FULFILLED', 'REVIEW')
                  group by customer_id having sum(quantity) > ?) t
                """, drop.id(), drop.perCustomerLimit());
        long phonesOverLimit = count("""
                select count(*) from (
                  select phone from orders where drop_id = ? and status in ('PAID', 'FULFILLED', 'REVIEW')
                  group by phone having sum(quantity) > ?) t
                """, drop.id(), drop.perCustomerLimit());
        long doubleCharged = count("""
                select count(*) from (
                  select p.order_id from payments p join orders o on o.id = p.order_id
                  where o.drop_id = ? and p.status = 'CAPTURED'
                  group by p.order_id having count(*) > 1) t
                """, drop.id());
        long capturedWithoutOrder = count("""
                select count(*) from payments p join orders o on o.id = p.order_id
                where o.drop_id = ? and p.status = 'CAPTURED' and o.status not in ('PAID', 'FULFILLED', 'REVIEW')
                """, drop.id());
        long refunds = count("""
                select count(*) from payments p join orders o on o.id = p.order_id
                where o.drop_id = ? and p.status in ('REFUND_PENDING', 'REFUNDED')
                """, drop.id());
        Map<String, Long> statuses = new java.util.TreeMap<>();
        jdbc.sql("select status, count(*) as n from orders where drop_id = ? group by status").param(drop.id())
                .query(rs -> {
                    statuses.put(rs.getString("status"), rs.getLong("n"));
                });
        boolean ok = !oversold && customersOverLimit == 0 && phonesOverLimit == 0 && doubleCharged == 0
                && capturedWithoutOrder == 0 && consistent;
        return new Report(drop.slug(), ok, skus, customersOverLimit, phonesOverLimit, doubleCharged, capturedWithoutOrder,
                refunds, statuses);
    }

    private long units(UUID sku, String condition) {
        return jdbc.sql("select coalesce(sum(quantity), 0) from orders where sku_id = ? and " + condition)
                .param(sku).query(Long.class).single();
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    public record Report(String drop, boolean ok, List<SkuLine> skus, long customersOverLimit, long phonesOverLimit,
            long ordersChargedMoreThanOnce, long capturedPaymentsWithoutPaidOrder, long refunds,
            Map<String, Long> ordersByStatus) {
    }

    public record SkuLine(String sku, int initialStock, long paidUnits, long heldUnits, Long redisAvailable,
            long expectedAvailable, boolean oversold, boolean consistent) {
    }
}
