package dev.mahindra.swiftcart.queue;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.inventory.InventoryService;
import dev.mahindra.swiftcart.inventory.Keys;

/**
 * A virtual waiting room in front of checkout. Shoppers get a numbered ticket; a leader admits tickets at the
 * drop's admit rate (what checkout can comfortably handle), and an admitted shopper gets a signed entry token.
 * Pods take minutes to scale; the line shapes the spike instantly.
 */
@Service
public class WaitingRoomService {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> JOIN = RedisScript.of(new ClassPathResource("scripts/join.lua"), List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ADMIT = RedisScript.of(new ClassPathResource("scripts/admit.lua"), List.class);

    private final StringRedisTemplate redis;
    private final InventoryService inventory;
    private final EntryTokenService entryTokens;
    private final Clock clock;

    public WaitingRoomService(StringRedisTemplate redis, InventoryService inventory, EntryTokenService entryTokens, Clock clock) {
        this.redis = redis;
        this.inventory = inventory;
        this.entryTokens = entryTokens;
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public Status join(Drop drop, UUID customer) {
        Instant now = clock.instant();
        switch (drop.phase(now)) {
            case UPCOMING -> throw ApiException.conflict("queue_not_open", "The waiting room opens at " + drop.queueOpensAt())
                    .with("queueOpensAt", drop.queueOpensAt());
            case ENDED -> throw ApiException.conflict("drop_closed", "This drop has ended");
            default -> {
            }
        }
        requireLoaded(drop);
        if (inventory.stock(drop.id()).soldOut()) {
            throw ApiException.conflict("sold_out", "Everything in this drop has sold");
        }
        List<Object> r = redis(() -> redis.execute(JOIN, List.of(Keys.queueTickets(drop.id()), Keys.queueSeq(drop.id())),
                customer.toString(), String.valueOf(drop.closesAt().toEpochMilli() + 86_400_000L)));
        return status(drop, customer, ((Number) r.get(0)).longValue());
    }

    /** Where the customer stands. Polled by the storefront; the response says how soon to poll again. */
    public Status status(Drop drop, UUID customer) {
        Object ticket = redis(() -> redis.opsForHash().get(Keys.queueTickets(drop.id()), customer.toString()));
        if (ticket == null) {
            return new Status(State.NOT_IN_QUEUE, 0, 0, 0, 5000, null, null);
        }
        return status(drop, customer, Long.parseLong(String.valueOf(ticket)));
    }

    private Status status(Drop drop, UUID customer, long ticket) {
        Instant now = clock.instant();
        if (drop.phase(now) == Drop.Phase.ENDED) {
            return new Status(State.CLOSED, ticket, 0, 0, 0, null, null);
        }
        InventoryService.Stock stock = inventory.stock(drop.id());
        if (stock.soldOut()) {
            return new Status(State.SOLD_OUT, ticket, 0, 0, 0, null, null);
        }
        long admitted = admitted(drop.id());
        if (drop.phase(now) == Drop.Phase.LIVE && ticket <= admitted) {
            EntryTokenService.Issued token = entryTokens.issue(customer, drop.id(), ticket);
            return new Status(State.ADMITTED, ticket, 0, 0, 0, token.token(), token.expiresAt());
        }
        long ahead = Math.max(0, ticket - admitted - 1);
        long etaSeconds = (ahead / Math.max(1, drop.admitPerSecond())) + 1;
        if (drop.phase(now) == Drop.Phase.QUEUE_OPEN) {
            etaSeconds += Math.max(0, drop.opensAt().getEpochSecond() - now.getEpochSecond());
        }
        // far back in the line: poll less often, so polling itself doesn't become the spike
        long pollMs = Math.min(15_000, 1_000 + ahead / Math.max(1, drop.admitPerSecond()) * 250);
        return new Status(State.WAITING, ticket, ahead, etaSeconds, pollMs, null, null);
    }

    /** Admit the next batch. Called once a second by the leader. Returns how many were admitted. */
    @SuppressWarnings("unchecked")
    public long admitNext(Drop drop) {
        List<Object> r = redis(() -> redis.execute(ADMIT,
                List.of(Keys.meta(drop.id()), Keys.stock(drop.id()), Keys.queueSeq(drop.id()),
                        Keys.queueAdmitted(drop.id()), Keys.queuePaused(drop.id())),
                String.valueOf(drop.admitPerSecond())));
        return ((Number) r.get(1)).longValue();
    }

    public void pause(Drop drop) {
        redis(() -> {
            redis.opsForValue().set(Keys.queuePaused(drop.id()), "1");
            return null;
        });
    }

    public void resume(Drop drop) {
        redis(() -> redis.delete(Keys.queuePaused(drop.id())));
    }

    public QueueStats stats(Drop drop) {
        long seq = longValue(redis(() -> redis.opsForValue().get(Keys.queueSeq(drop.id()))));
        long admitted = admitted(drop.id());
        boolean paused = Boolean.TRUE.equals(redis(() -> redis.hasKey(Keys.queuePaused(drop.id()))));
        return new QueueStats(seq, admitted, Math.max(0, seq - admitted), paused);
    }

    private long admitted(UUID drop) {
        return longValue(redis(() -> redis.opsForValue().get(Keys.queueAdmitted(drop))));
    }

    private void requireLoaded(Drop drop) {
        if (!"READY".equals(inventory.state(drop.id()))) {
            throw ApiException.unavailable("inventory_unavailable", "The drop is getting ready; retry in a moment");
        }
    }

    private static long longValue(String s) {
        return s == null ? 0 : Long.parseLong(s);
    }

    private static <T> T redis(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        } catch (DataAccessException e) {
            throw ApiException.unavailable("queue_unavailable", "The waiting room is briefly unavailable; retry in a moment");
        }
    }

    public enum State {
        NOT_IN_QUEUE, WAITING, ADMITTED, SOLD_OUT, CLOSED
    }

    public record Status(State state, long ticket, long aheadOfYou, long estimatedWaitSeconds, long pollAfterMs,
            String entryToken, Instant entryTokenExpiresAt) {
    }

    public record QueueStats(long joined, long admitted, long waiting, boolean paused) {
    }
}
