package dev.mahindra.swiftcart.drop;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A limited release: a fixed number of units of a few SKUs, sold from a set time. */
public record Drop(UUID id, String slug, String name, String description, String imageUrl, Instant queueOpensAt,
        Instant opensAt, Instant closesAt, int perCustomerLimit, int admitPerSecond, int reservationTtlSeconds,
        int expectedPeakRps, String status, List<Sku> skus) {

    public Optional<Sku> sku(String code) {
        return skus.stream().filter(s -> s.code().equals(code)).findFirst();
    }

    public Duration reservationTtl() {
        return Duration.ofSeconds(reservationTtlSeconds);
    }

    public boolean armed() {
        return "ARMED".equals(status);
    }

    /** Time-based phase; whether it's sold out comes from live inventory. */
    public Phase phase(Instant now) {
        if (now.isBefore(queueOpensAt)) {
            return Phase.UPCOMING;
        }
        if (now.isBefore(opensAt)) {
            return Phase.QUEUE_OPEN;
        }
        if (now.isBefore(closesAt)) {
            return Phase.LIVE;
        }
        return Phase.ENDED;
    }

    public enum Phase {
        UPCOMING, QUEUE_OPEN, LIVE, ENDED
    }

    public record Sku(UUID id, UUID dropId, String code, String name, long priceMinor, String currency, int initialStock) {
    }
}
