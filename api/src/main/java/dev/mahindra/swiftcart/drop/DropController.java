package dev.mahindra.swiftcart.drop;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.inventory.InventoryService;

/** Public, cacheable drop pages: the CDN absorbs catalogue traffic, the origin serves checkout. */
@RestController
@RequestMapping("/api/drops")
class DropController {

    private final DropRepository repository;
    private final DropCatalog catalog;
    private final InventoryService inventory;
    private final Clock clock;

    DropController(DropRepository repository, DropCatalog catalog, InventoryService inventory, Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.inventory = inventory;
        this.clock = clock;
    }

    @GetMapping
    ResponseEntity<List<DropView>> list() {
        Instant now = clock.instant();
        List<DropView> views = repository.findVisible().stream().map(d -> DropView.of(d, now)).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic()).body(views);
    }

    @GetMapping("/{slug}")
    ResponseEntity<DropView> get(@PathVariable String slug) {
        Drop d = catalog.visibleBySlug(slug);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(DropView.of(d, clock.instant()));
    }

    /** Live stock. Cached for one second at the edge: a million refreshes become one origin hit per second. */
    @GetMapping("/{slug}/stock")
    ResponseEntity<StockView> stock(@PathVariable String slug) {
        Drop d = catalog.visibleBySlug(slug);
        Instant now = clock.instant();
        StockView view;
        try {
            InventoryService.Stock s = inventory.stock(d.id());
            String state = s.soldOut() && d.phase(now) != Drop.Phase.ENDED ? "SOLD_OUT" : d.phase(now).name();
            view = new StockView(state, s.available(), s.pendingReservations(), now);
        } catch (ApiException e) {
            // stock is a nice-to-have on the page; the drop page still renders with "unknown"
            view = new StockView(d.phase(now).name(), Map.of(), 0, now);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(1)).cachePublic()).body(view);
    }

    record DropView(String slug, String name, String description, String imageUrl, Instant queueOpensAt,
            Instant opensAt, Instant closesAt, int perCustomerLimit, int reservationTtlSeconds, String phase,
            List<SkuView> skus) {

        static DropView of(Drop d, Instant now) {
            return new DropView(d.slug(), d.name(), d.description(), d.imageUrl(), d.queueOpensAt(), d.opensAt(),
                    d.closesAt(), d.perCustomerLimit(), d.reservationTtlSeconds(), d.phase(now).name(),
                    d.skus().stream().map(s -> new SkuView(s.code(), s.name(), s.priceMinor(), s.currency(), s.initialStock())).toList());
        }
    }

    record SkuView(String code, String name, long priceMinor, String currency, int totalUnits) {
    }

    record StockView(String state, Map<String, Long> available, long heldInCheckout, Instant asOf) {
    }
}
