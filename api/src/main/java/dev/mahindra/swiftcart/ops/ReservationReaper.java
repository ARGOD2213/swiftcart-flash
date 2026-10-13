package dev.mahindra.swiftcart.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.drop.DropRepository;
import dev.mahindra.swiftcart.inventory.InventoryService;
import dev.mahindra.swiftcart.inventory.InventoryService.ReleaseResult;
import dev.mahindra.swiftcart.order.Order;
import dev.mahindra.swiftcart.order.OrderRepository;

/**
 * Gives unpaid reservations back when their hold time is up, once a second, on one pod.
 *
 * <p>Two passes: the Redis expiry set (the normal path), and a database safety net for pending orders whose hold
 * ended minutes ago, in case Redis lost the expiry entry. Releasing is idempotent and never touches a paid
 * reservation, so a payment racing the reaper either wins (units kept) or arrives after and is rescued/refunded.
 */
@Component
@ConditionalOnProperty(name = "swiftcart.jobs.enabled", havingValue = "true")
public class ReservationReaper {

    private static final Logger log = LoggerFactory.getLogger(ReservationReaper.class);
    private static final Duration SAFETY_NET_GRACE = Duration.ofMinutes(2);

    private final DropRepository drops;
    private final InventoryService inventory;
    private final OrderRepository orders;
    private final Locks locks;
    private final Clock clock;

    public ReservationReaper(DropRepository drops, InventoryService inventory, OrderRepository orders, Locks locks, Clock clock) {
        this.drops = drops;
        this.inventory = inventory;
        this.orders = orders;
        this.locks = locks;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${swiftcart.jobs.reaper-interval-ms:1000}", initialDelay = 2000)
    public void scheduled() {
        try {
            locks.asLeader("reaper", this::reap, 0);
        } catch (RuntimeException e) {
            log.warn("Reaper round failed: {}", e.getMessage());
        }
    }

    /** Returns how many reservations were released. */
    public int reap() {
        Instant now = clock.instant();
        int released = 0;
        for (Drop drop : drops.findActive(now)) {
            try {
                for (UUID reservation : inventory.due(drop.id(), now, 500)) {
                    ReleaseResult r = inventory.release(drop.id(), reservation, true);
                    if (r == ReleaseResult.RELEASED || r == ReleaseResult.MISSING) {
                        orders.expire(reservation);
                        released += r == ReleaseResult.RELEASED ? 1 : 0;
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Reaper skipped drop {}: {}", drop.slug(), e.getMessage());
            }
        }
        for (Order o : orders.overdue(now.minus(SAFETY_NET_GRACE), 200)) {
            try {
                ReleaseResult r = inventory.release(o.dropId(), o.reservationId(), true);
                if (r == ReleaseResult.RELEASED || r == ReleaseResult.MISSING) {
                    orders.expire(o.reservationId());
                }
            } catch (RuntimeException e) {
                log.warn("Safety net skipped order {}: {}", o.id(), e.getMessage());
            }
        }
        if (released > 0) {
            log.info("Released {} expired reservations", released);
        }
        return released;
    }
}
