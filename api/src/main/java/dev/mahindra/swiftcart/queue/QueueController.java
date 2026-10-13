package dev.mahindra.swiftcart.queue;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.mahindra.swiftcart.auth.CurrentCustomer;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.drop.DropCatalog;

@RestController
@RequestMapping("/api/drops/{slug}/queue")
class QueueController {

    private final DropCatalog drops;
    private final WaitingRoomService room;

    QueueController(DropCatalog drops, WaitingRoomService room) {
        this.drops = drops;
        this.room = room;
    }

    /** Join (or rejoin: same ticket) the waiting room. */
    @PostMapping
    ResponseEntity<WaitingRoomService.Status> join(@PathVariable String slug, JwtAuthenticationToken auth) {
        Drop drop = drops.visibleBySlug(slug);
        return noStore(room.join(drop, CurrentCustomer.id(auth)));
    }

    @GetMapping("/me")
    ResponseEntity<WaitingRoomService.Status> me(@PathVariable String slug, JwtAuthenticationToken auth) {
        Drop drop = drops.visibleBySlug(slug);
        return noStore(room.status(drop, CurrentCustomer.id(auth)));
    }

    private static ResponseEntity<WaitingRoomService.Status> noStore(WaitingRoomService.Status s) {
        // personal and carries a token: never cached by a CDN or browser
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(s);
    }
}
