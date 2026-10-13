package dev.mahindra.swiftcart.inventory;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.ops.Locks;
import dev.mahindra.swiftcart.order.Order;
import dev.mahindra.swiftcart.order.OrderRepository;

/**
 * Rebuilds a drop's Redis state from the order log in Postgres: what's left per SKU, how much each customer and
 * phone holds, and every live reservation. Used when Redis lost data (failover to a replica that missed the last
 * writes, a flush, eviction) or when an operator wants to resync.
 *
 * <p>While it runs the drop is marked REBUILDING: checkouts get 503 and payment events are retried later, so
 * nothing is sold or released against half-written state. Reservations that existed only in Redis (a checkout
 * that crashed before writing its order) are dropped, which only returns units to stock.
 */
@Component
public class InventoryRebuilder {

    private static final Logger log = LoggerFactory.getLogger(InventoryRebuilder.class);

    private final StringRedisTemplate redis;
    private final OrderRepository orders;
    private final TransactionTemplate tx;
    private final Locks locks;
    private final Clock clock;

    public InventoryRebuilder(StringRedisTemplate redis, OrderRepository orders, TransactionTemplate tx, Locks locks, Clock clock) {
        this.redis = redis;
        this.orders = orders;
        this.tx = tx;
        this.locks = locks;
        this.clock = clock;
    }

    public Result rebuild(Drop d) {
        UUID drop = d.id();
        // one rebuild per drop at a time, across all pods
        Result result = tx.execute(status -> {
            if (!locks.tryTransactionLock("rebuild:" + drop)) {
                log.info("Drop {} is already being rebuilt by another instance", d.slug());
                return null;
            }
            redis.opsForHash().put(Keys.meta(drop), "state", "REBUILDING");
            Instant now = clock.instant();
            List<OrderRepository.Holding> holdings = orders.holdings(drop, now);

            Map<UUID, String> skuCodes = new HashMap<>();
            Map<String, Long> stock = new HashMap<>();
            d.skus().forEach(s -> {
                skuCodes.put(s.id(), s.code());
                stock.put(s.code(), (long) s.initialStock());
            });
            Map<String, Long> byCustomer = new HashMap<>();
            Map<String, Long> byPhone = new HashMap<>();
            for (OrderRepository.Holding h : holdings) {
                stock.merge(skuCodes.get(h.skuId()), (long) -h.quantity(), Long::sum);
                byCustomer.merge(h.customerId().toString(), (long) h.quantity(), Long::sum);
                byPhone.merge(h.phone(), (long) h.quantity(), Long::sum);
            }
            long oversold = stock.values().stream().filter(v -> v < 0).mapToLong(v -> -v).sum();
            if (oversold > 0) {
                log.error("Drop {}: the order log holds {} more units than exist. Stock is clamped to 0.", d.slug(), oversold);
            }
            stock.replaceAll((k, v) -> Math.max(0, v));

            long expireAt = InventoryService.expireAt(d);
            deleteReservations(drop);
            redis.delete(List.of(Keys.stock(drop), Keys.heldByCustomer(drop), Keys.heldByPhone(drop), Keys.expiry(drop)));
            stock.forEach((code, units) -> redis.opsForHash().put(Keys.stock(drop), code, String.valueOf(units)));
            byCustomer.forEach((c, units) -> redis.opsForHash().put(Keys.heldByCustomer(drop), c, String.valueOf(units)));
            byPhone.forEach((p, units) -> redis.opsForHash().put(Keys.heldByPhone(drop), p, String.valueOf(units)));
            int pending = 0;
            for (OrderRepository.Holding h : holdings) {
                boolean held = h.status() == Order.Status.PENDING_PAYMENT;
                String key = Keys.reservation(drop, h.reservationId());
                redis.opsForHash().putAll(key, Map.of(
                        "sku", skuCodes.get(h.skuId()),
                        "qty", String.valueOf(h.quantity()),
                        "cust", h.customerId().toString(),
                        "phone", h.phone(),
                        "state", held ? "R" : "C",
                        "exp", String.valueOf(h.reservedUntil().toEpochMilli())));
                redis.expireAt(key, Instant.ofEpochMilli(expireAt));
                if (held) {
                    redis.opsForZSet().add(Keys.expiry(drop), h.reservationId().toString(), h.reservedUntil().toEpochMilli());
                    pending++;
                }
            }
            for (String key : List.of(Keys.stock(drop), Keys.heldByCustomer(drop), Keys.heldByPhone(drop), Keys.expiry(drop))) {
                redis.expireAt(key, Instant.ofEpochMilli(expireAt));
            }
            redis.opsForHash().putAll(Keys.meta(drop), Map.of(
                    "opens", String.valueOf(d.opensAt().toEpochMilli()),
                    "closes", String.valueOf(d.closesAt().toEpochMilli()),
                    "limit", String.valueOf(d.perCustomerLimit()),
                    "ttl", String.valueOf(d.reservationTtl().toMillis()),
                    "expireAt", String.valueOf(expireAt),
                    "state", "READY"));
            redis.expireAt(Keys.meta(drop), Instant.ofEpochMilli(expireAt));
            log.info("Rebuilt inventory of drop {}: stock {}, {} live reservations, {} paid holdings", d.slug(), stock,
                    pending, holdings.size() - pending);
            return new Result(Map.copyOf(stock), pending, holdings.size() - pending, oversold);
        });
        return result == null ? new Result(Map.of(), 0, 0, 0) : result;
    }

    private void deleteReservations(UUID drop) {
        ScanOptions options = ScanOptions.scanOptions().match(Keys.reservationPattern(drop)).count(500).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            Set<String> batch = new java.util.HashSet<>();
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() == 500) {
                    redis.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                redis.delete(batch);
            }
        }
    }

    public record Result(Map<String, Long> stock, int liveReservations, int paidHoldings, long oversoldUnits) {
    }
}
