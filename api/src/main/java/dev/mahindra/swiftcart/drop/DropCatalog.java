package dev.mahindra.swiftcart.drop;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import dev.mahindra.swiftcart.api.ApiException;

/**
 * Hot-path lookups of drops (checkout, queue polling) without a database round trip per request.
 * Safe because an armed drop is frozen: prices, limits and times can't change once stock is loaded.
 */
@Component
public class DropCatalog {

    private static final Duration TTL = Duration.ofSeconds(5);

    private final DropRepository drops;
    private final Clock clock;
    private final Map<String, Cached> bySlug = new ConcurrentHashMap<>();
    private final Map<UUID, Cached> byId = new ConcurrentHashMap<>();

    public DropCatalog(DropRepository drops, Clock clock) {
        this.drops = drops;
        this.clock = clock;
    }

    /** A drop shoppers can act on (armed or settled). Drafts are invisible: 404, not 403. */
    public Drop visibleBySlug(String slug) {
        Instant now = clock.instant();
        Cached c = bySlug.get(slug);
        if (c == null || c.expires.isBefore(now)) {
            Optional<Drop> found = drops.findBySlug(slug);
            c = new Cached(found.orElse(null), now.plus(TTL));
            bySlug.put(slug, c);
            found.ifPresent(d -> byId.put(d.id(), new Cached(d, now.plus(TTL))));
        }
        if (c.drop == null || "DRAFT".equals(c.drop.status())) {
            throw ApiException.notFound("drop_not_found", "No drop called " + slug);
        }
        return c.drop;
    }

    public Drop byId(UUID id) {
        Instant now = clock.instant();
        Cached c = byId.get(id);
        if (c == null || c.drop == null || c.expires.isBefore(now)) {
            Drop d = drops.findById(id).orElseThrow(() -> ApiException.notFound("drop_not_found", "No such drop"));
            c = new Cached(d, now.plus(TTL));
            byId.put(id, c);
        }
        return c.drop;
    }

    public void evict(Drop d) {
        bySlug.remove(d.slug());
        byId.remove(d.id());
    }

    private record Cached(Drop drop, Instant expires) {
    }
}
