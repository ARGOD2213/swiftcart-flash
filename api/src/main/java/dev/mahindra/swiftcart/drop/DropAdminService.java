package dev.mahindra.swiftcart.drop;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.inventory.InventoryRebuilder;
import dev.mahindra.swiftcart.inventory.InventoryService;

@Service
public class DropAdminService {

    private static final Logger log = LoggerFactory.getLogger(DropAdminService.class);

    private final DropRepository drops;
    private final DropCatalog catalog;
    private final InventoryService inventory;
    private final InventoryRebuilder rebuilder;
    private final Clock clock;

    public DropAdminService(DropRepository drops, DropCatalog catalog, InventoryService inventory, InventoryRebuilder rebuilder,
            Clock clock) {
        this.drops = drops;
        this.catalog = catalog;
        this.inventory = inventory;
        this.rebuilder = rebuilder;
        this.clock = clock;
    }

    public Drop create(NewDrop req) {
        if (!req.queueOpensAt().isBefore(req.opensAt()) && !req.queueOpensAt().equals(req.opensAt())) {
            throw ApiException.badRequest("invalid_times", "The waiting room must open no later than the drop");
        }
        if (!req.opensAt().isBefore(req.closesAt())) {
            throw ApiException.badRequest("invalid_times", "The drop must close after it opens");
        }
        Set<String> codes = new HashSet<>();
        for (NewSku s : req.skus()) {
            if (!codes.add(s.code())) {
                throw ApiException.badRequest("duplicate_sku", "SKU code " + s.code() + " appears twice");
            }
        }
        UUID id = UUID.randomUUID();
        List<Drop.Sku> skus = req.skus().stream()
                .map(s -> new Drop.Sku(UUID.randomUUID(), id, s.code(), s.name(), s.priceMinor(),
                        s.currency() == null ? "INR" : s.currency(), s.initialStock()))
                .toList();
        Drop d = new Drop(id, req.slug(), req.name(), req.description() == null ? "" : req.description(), req.imageUrl(),
                req.queueOpensAt(), req.opensAt(), req.closesAt(), req.perCustomerLimit(), req.admitPerSecond(),
                req.reservationTtlSeconds() == null ? 600 : req.reservationTtlSeconds(),
                req.expectedPeakRps() == null ? 500 : req.expectedPeakRps(), "DRAFT", skus);
        try {
            return drops.insert(d);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("slug_taken", "A drop called " + req.slug() + " already exists");
        }
    }

    /**
     * Loads stock into Redis and freezes the drop. Safe to call again: an armed drop whose Redis data is intact
     * is left alone (live stock is never reset); one whose Redis data is gone is rebuilt from the order log.
     */
    public Drop arm(UUID id) {
        Drop d = drops.findById(id).orElseThrow(() -> ApiException.notFound("drop_not_found", "No such drop"));
        Instant now = clock.instant();
        if ("SETTLED".equals(d.status())) {
            throw ApiException.conflict("drop_settled", "This drop is settled and can't be armed again");
        }
        if (!now.isBefore(d.closesAt())) {
            throw ApiException.conflict("drop_closed", "This drop has already closed");
        }
        if (d.skus().isEmpty()) {
            throw ApiException.badRequest("no_skus", "Add at least one SKU before arming");
        }
        if ("DRAFT".equals(d.status())) {
            inventory.arm(d);
            drops.transition(id, "DRAFT", "ARMED");
            log.info("Armed drop {} with {} SKUs", d.slug(), d.skus().size());
        } else if ("MISSING".equals(inventory.state(id))) {
            log.warn("Drop {} is armed but its inventory is missing from Redis; rebuilding from the order log", d.slug());
            rebuilder.rebuild(d);
        }
        catalog.evict(d);
        return drops.findById(id).orElseThrow();
    }

    public record NewDrop(String slug, String name, String description, String imageUrl, Instant queueOpensAt,
            Instant opensAt, Instant closesAt, int perCustomerLimit, int admitPerSecond, Integer reservationTtlSeconds,
            Integer expectedPeakRps, List<NewSku> skus) {
    }

    public record NewSku(String code, String name, long priceMinor, String currency, int initialStock) {
    }
}
