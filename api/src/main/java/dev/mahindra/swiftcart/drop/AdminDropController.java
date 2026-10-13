package dev.mahindra.swiftcart.drop;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.inventory.InventoryRebuilder;
import dev.mahindra.swiftcart.ops.AuditService;
import dev.mahindra.swiftcart.queue.WaitingRoomService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Drop operations for staff: create, arm, watch the line, pause it, rebuild inventory, audit. */
@RestController
@RequestMapping("/api/admin/drops")
class AdminDropController {

    private final DropAdminService admin;
    private final DropRepository drops;
    private final InventoryRebuilder rebuilder;
    private final WaitingRoomService room;
    private final AuditService audit;
    private final DropCatalog catalog;

    AdminDropController(DropAdminService admin, DropRepository drops, InventoryRebuilder rebuilder, WaitingRoomService room,
            AuditService audit, DropCatalog catalog) {
        this.admin = admin;
        this.drops = drops;
        this.rebuilder = rebuilder;
        this.room = room;
        this.audit = audit;
        this.catalog = catalog;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateDrop body) {
        Drop d = admin.create(new DropAdminService.NewDrop(body.slug(), body.name(), body.description(), body.imageUrl(),
                body.queueOpensAt(), body.opensAt(), body.closesAt(), body.perCustomerLimit(), body.admitPerSecond(),
                body.reservationTtlSeconds(), body.expectedPeakRps(),
                body.skus().stream().map(s -> new DropAdminService.NewSku(s.code(), s.name(), s.priceMinor(), s.currency(),
                        s.initialStock())).toList()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", d.id(), "slug", d.slug(), "status", d.status()));
    }

    @PostMapping("/{id}/arm")
    Map<String, Object> arm(@PathVariable UUID id) {
        Drop d = admin.arm(id);
        return Map.of("id", d.id(), "slug", d.slug(), "status", d.status());
    }

    @PostMapping("/{id}/rebuild-inventory")
    InventoryRebuilder.Result rebuild(@PathVariable UUID id) {
        Drop d = find(id);
        if (!d.armed()) {
            throw ApiException.conflict("drop_not_armed", "Only an armed drop has inventory to rebuild");
        }
        return rebuilder.rebuild(d);
    }

    @GetMapping("/{id}/queue")
    WaitingRoomService.QueueStats queue(@PathVariable UUID id) {
        return room.stats(find(id));
    }

    @PostMapping("/{id}/admissions/pause")
    WaitingRoomService.QueueStats pause(@PathVariable UUID id) {
        Drop d = find(id);
        room.pause(d);
        return room.stats(d);
    }

    @PostMapping("/{id}/admissions/resume")
    WaitingRoomService.QueueStats resume(@PathVariable UUID id) {
        Drop d = find(id);
        room.resume(d);
        return room.stats(d);
    }

    @GetMapping("/{id}/audit")
    AuditService.Report audit(@PathVariable UUID id) {
        return audit.audit(find(id));
    }

    private Drop find(UUID id) {
        Drop d = drops.findById(id).orElseThrow(() -> ApiException.notFound("drop_not_found", "No such drop"));
        catalog.evict(d);
        return d;
    }

    record CreateDrop(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{2,59}$", message = "3-60 lowercase letters, digits and dashes") String slug,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description,
            @Size(max = 500) String imageUrl,
            @NotNull Instant queueOpensAt,
            @NotNull Instant opensAt,
            @NotNull Instant closesAt,
            @Min(1) @Max(10) int perCustomerLimit,
            @Min(1) @Max(100000) int admitPerSecond,
            @Min(30) @Max(3600) Integer reservationTtlSeconds,
            @Min(1) Integer expectedPeakRps,
            @NotEmpty @Size(max = 50) List<@Valid CreateSku> skus) {
    }

    record CreateSku(
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{0,39}$", message = "uppercase letters, digits and dashes") String code,
            @NotBlank @Size(max = 120) String name,
            @Min(100) long priceMinor,
            @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @Min(0) @Max(10_000_000) int initialStock) {
    }
}
