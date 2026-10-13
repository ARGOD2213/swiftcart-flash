package dev.mahindra.swiftcart.checkout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.auth.CurrentCustomer;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.drop.DropCatalog;
import dev.mahindra.swiftcart.order.OrderView;
import dev.mahindra.swiftcart.queue.EntryTokenService;
import jakarta.validation.Valid;

/**
 * {@code POST /api/drops/{slug}/checkout} with headers {@code Idempotency-Key} and {@code X-Entry-Token}.
 *
 * <p>Order of checks: valid key, entry token (through the waiting room), idempotency (replay or in-progress),
 * then the reservation. Answers that depend only on the request (sold out, limit reached, ...) are stored
 * against the key, so a retry gets the same answer. Transient failures (503) are not, so a retry can succeed.
 */
@RestController
class CheckoutController {

    private final DropCatalog drops;
    private final EntryTokenService entryTokens;
    private final IdempotencyService idempotency;
    private final CheckoutService checkout;
    private final ObjectMapper json;

    CheckoutController(DropCatalog drops, EntryTokenService entryTokens, IdempotencyService idempotency,
            CheckoutService checkout, ObjectMapper json) {
        this.drops = drops;
        this.entryTokens = entryTokens;
        this.idempotency = idempotency;
        this.checkout = checkout;
        this.json = json;
    }

    @PostMapping("/api/drops/{slug}/checkout")
    ResponseEntity<String> checkout(@PathVariable String slug, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Entry-Token", required = false) String entryToken,
            @Valid @RequestBody CheckoutRequest body, JwtAuthenticationToken auth) {
        IdempotencyService.validate(key);
        UUID customer = CurrentCustomer.id(auth);
        Drop drop = drops.visibleBySlug(slug);
        String hash = hash(slug, body);
        try {
            entryTokens.verify(entryToken, customer, drop.id());
        } catch (ApiException e) {
            // a retry of a checkout that already completed still gets its order, even after the entry token expired
            Optional<IdempotencyService.Stored> done = idempotency.completed(customer, key, hash);
            if (done.isPresent()) {
                return replay(done.get());
            }
            throw e;
        }

        Optional<IdempotencyService.Stored> stored = idempotency.begin(customer, key, hash);
        if (stored.isPresent()) {
            return replay(stored.get());
        }
        try {
            OrderView order = checkout.checkout(drop, customer, key, body);
            String response = write(order);
            idempotency.complete(customer, key, HttpStatus.CREATED.value(), response);
            return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(response);
        } catch (ApiException e) {
            if (e.status().is4xxClientError()) {
                // a definite answer for this request: retries with the same key get it too
                idempotency.complete(customer, key, e.status().value(), problemJson(e));
            } else {
                idempotency.release(customer, key);
            }
            throw e;
        } catch (RuntimeException e) {
            idempotency.release(customer, key);
            throw e;
        }
    }

    private static ResponseEntity<String> replay(IdempotencyService.Stored s) {
        return ResponseEntity.status(s.status()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replayed", "true").body(s.body());
    }

    private String hash(String slug, CheckoutRequest body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((slug + "|" + json.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String problemJson(ApiException e) {
        java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("type", "about:blank");
        p.put("title", e.status().getReasonPhrase());
        p.put("status", e.status().value());
        p.put("detail", e.getMessage());
        p.put("code", e.code());
        p.putAll(e.extras());
        return write(p);
    }
}
