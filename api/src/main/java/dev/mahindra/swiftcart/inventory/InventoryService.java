package dev.mahindra.swiftcart.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.drop.Drop;

/**
 * Stock during a drop lives in Redis and changes only through Lua scripts, which Redis runs one at a time:
 * the check ("is there stock? is the customer under the limit?") and the decrement can't be interleaved with
 * another buyer's, so the counter can't go below zero.
 *
 * <p>If Redis is unreachable or not ready, callers get {@code 503 inventory_unavailable}. The service never
 * guesses stock from anywhere else while selling, because a guess is how oversells happen.
 */
@Component
public class InventoryService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InventoryService.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE = RedisScript.of(new ClassPathResource("scripts/reserve.lua"), List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RELEASE = RedisScript.of(new ClassPathResource("scripts/release.lua"), List.class);
    private static final RedisScript<String> CONFIRM = RedisScript.of(new ClassPathResource("scripts/confirm.lua"), String.class);
    private static final RedisScript<String> ARM = RedisScript.of(new ClassPathResource("scripts/arm.lua"), String.class);

    /** Keys live until a week after the drop closes, so audits and late payments still find them. */
    static final long KEEP_AFTER_CLOSE_MS = 7L * 24 * 3600 * 1000;

    private final StringRedisTemplate redis;

    public InventoryService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Loads stock. Returns false if the drop was already armed (its live stock is left untouched). */
    public boolean arm(Drop d) {
        List<String> args = new ArrayList<>(List.of(
                String.valueOf(d.opensAt().toEpochMilli()),
                String.valueOf(d.closesAt().toEpochMilli()),
                String.valueOf(d.perCustomerLimit()),
                String.valueOf(d.reservationTtl().toMillis()),
                String.valueOf(expireAt(d))));
        d.skus().forEach(s -> {
            args.add(s.code());
            args.add(String.valueOf(s.initialStock()));
        });
        String result = call(() -> redis.execute(ARM, List.of(Keys.meta(d.id()), Keys.stock(d.id())), args.toArray()));
        return "ARMED".equals(result);
    }

    @SuppressWarnings("unchecked")
    public ReserveResult reserve(UUID drop, String sku, int quantity, UUID customer, String phone, UUID reservation) {
        List<Object> r = call(() -> redis.execute(RESERVE,
                List.of(Keys.meta(drop), Keys.stock(drop), Keys.heldByCustomer(drop), Keys.heldByPhone(drop),
                        Keys.reservation(drop, reservation), Keys.expiry(drop)),
                sku, String.valueOf(quantity), customer.toString(), phone, reservation.toString()));
        long expires = num(r.get(2));
        return new ReserveResult(ReserveResult.Code.valueOf(String.valueOf(r.get(0))), num(r.get(1)),
                expires > 0 ? Instant.ofEpochMilli(expires) : null);
    }

    /** {@code onlyIfExpired}: the reaper's mode; a cancel releases a held reservation immediately. */
    @SuppressWarnings("unchecked")
    public ReleaseResult release(UUID drop, UUID reservation, boolean onlyIfExpired) {
        List<Object> r = call(() -> redis.execute(RELEASE,
                List.of(Keys.meta(drop), Keys.stock(drop), Keys.heldByCustomer(drop), Keys.heldByPhone(drop),
                        Keys.reservation(drop, reservation), Keys.expiry(drop)),
                reservation.toString(), onlyIfExpired ? "expire" : "cancel"));
        return ReleaseResult.valueOf(String.valueOf(r.get(0)));
    }

    public ConfirmResult confirm(UUID drop, UUID reservation) {
        String r = call(() -> redis.execute(CONFIRM,
                List.of(Keys.meta(drop), Keys.reservation(drop, reservation), Keys.expiry(drop)), reservation.toString()));
        return ConfirmResult.valueOf(r);
    }

    /** Units left per SKU code, in SKU order of the map Redis returns. Empty if the drop isn't loaded. */
    public Map<String, Long> available(UUID drop) {
        Map<Object, Object> raw = call(() -> redis.opsForHash().entries(Keys.stock(drop)));
        Map<String, Long> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), Long.parseLong(String.valueOf(v))));
        return out;
    }

    /** Held-but-unpaid reservations. While any exist, sold-out units may still come back. */
    public long pendingReservations(UUID drop) {
        Long n = call(() -> redis.opsForZSet().zCard(Keys.expiry(drop)));
        return n == null ? 0 : n;
    }

    public String state(UUID drop) {
        Object s = call(() -> redis.opsForHash().get(Keys.meta(drop), "state"));
        return s == null ? "MISSING" : String.valueOf(s);
    }

    /** Reservation ids whose hold time is up, oldest first. */
    public List<UUID> due(UUID drop, Instant now, int max) {
        Set<String> ids = call(() -> redis.opsForZSet().rangeByScore(Keys.expiry(drop), 0, now.toEpochMilli(), 0, max));
        return ids == null ? List.of() : ids.stream().map(UUID::fromString).toList();
    }

    public Stock stock(UUID drop) {
        Map<String, Long> available = available(drop);
        long pending = pendingReservations(drop);
        long total = available.values().stream().mapToLong(Long::longValue).sum();
        return new Stock(available, pending, total <= 0 && pending == 0 && !available.isEmpty());
    }

    static long expireAt(Drop d) {
        return d.closesAt().toEpochMilli() + KEEP_AFTER_CLOSE_MS;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }

    private static <T> T call(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        } catch (DataAccessException e) {
            log.warn("Redis call failed: {}", e.getMessage());
            throw ApiException.unavailable("inventory_unavailable", "Inventory is briefly unavailable; retry in a moment");
        }
    }

    public record ReserveResult(Code code, long value, Instant expiresAt) {

        public boolean held() {
            return code == Code.OK || code == Code.DUPLICATE;
        }

        public enum Code {
            OK, DUPLICATE, NOT_READY, NOT_OPEN, CLOSED, SOLD_OUT, INSUFFICIENT, LIMIT, PHONE_LIMIT, UNKNOWN_SKU, BAD_QTY
        }
    }

    public enum ReleaseResult {
        RELEASED, MISSING, CONFIRMED, NOT_EXPIRED, NOT_READY
    }

    public enum ConfirmResult {
        CONFIRMED, ALREADY, MISSING, NOT_READY
    }

    /** {@code soldOut}: nothing left and nothing held that could come back. */
    public record Stock(Map<String, Long> available, long pendingReservations, boolean soldOut) {
    }
}
