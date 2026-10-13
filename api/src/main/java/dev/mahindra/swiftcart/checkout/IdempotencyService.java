package dev.mahindra.swiftcart.checkout;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import dev.mahindra.swiftcart.api.ApiException;

/**
 * Stripe-style idempotency keys for checkout. The first request with a key does the work and its response is
 * stored; any retry with the same key gets that same response back (same order, no second reservation).
 * <ul>
 *   <li>Same key, different request body: 422, because that's a client bug, not a retry.</li>
 *   <li>Same key while the first request is still running (double click): 409 with Retry-After.</li>
 *   <li>A request that died mid-flight (pod killed) is taken over after {@link #ABANDONED_AFTER}.</li>
 *   <li>Unexpected server errors are not stored, so the client can retry the same key.</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    static final Duration ABANDONED_AFTER = Duration.ofSeconds(60);
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_\\-:.]{8,100}$");

    private final JdbcClient jdbc;

    public IdempotencyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static void validate(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ApiException.badRequest("invalid_idempotency_key",
                    "Send an Idempotency-Key header of 8-100 characters (letters, digits, - _ : .), e.g. a UUID per checkout attempt");
        }
    }

    /** Empty: this request owns the key and should do the work. Present: replay this stored response. */
    public Optional<Stored> begin(UUID customer, String key, String requestHash) {
        int inserted = jdbc.sql("""
                insert into idempotency_keys (customer_id, key, request_hash, status) values (?, ?, ?, 'IN_PROGRESS')
                on conflict do nothing
                """).params(customer, key, requestHash).update();
        if (inserted == 1) {
            return Optional.empty();
        }
        Row row = jdbc.sql("""
                select request_hash, status, response_status, response_body,
                       created_at < now() - make_interval(secs => ?) as abandoned
                from idempotency_keys where customer_id = ? and key = ?
                """).params(ABANDONED_AFTER.toSeconds(), customer, key)
                .query((rs, i) -> new Row(rs.getString("request_hash"), rs.getString("status"),
                        (Integer) rs.getObject("response_status"), rs.getString("response_body"), rs.getBoolean("abandoned")))
                .optional()
                .orElse(null);
        if (row == null) {
            // deleted between our insert attempt and the read (an abandoned request cleaned up): try once more
            return begin(customer, key, requestHash);
        }
        if (!row.requestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused",
                    "This Idempotency-Key was already used for a different request; use a new key for a new checkout");
        }
        if ("COMPLETED".equals(row.status())) {
            return Optional.of(new Stored(row.responseStatus(), row.responseBody()));
        }
        if (row.abandoned()) {
            int taken = jdbc.sql("""
                    update idempotency_keys set created_at = now()
                    where customer_id = ? and key = ? and status = 'IN_PROGRESS' and created_at < now() - make_interval(secs => ?)
                    """).params(customer, key, ABANDONED_AFTER.toSeconds()).update();
            if (taken == 1) {
                return Optional.empty();
            }
        }
        throw ApiException.conflict("request_in_progress", "This checkout is already being processed").retryAfter(1);
    }

    /** A finished response for exactly this request, if there is one. */
    public Optional<Stored> completed(UUID customer, String key, String requestHash) {
        return jdbc.sql("""
                select response_status, response_body from idempotency_keys
                where customer_id = ? and key = ? and request_hash = ? and status = 'COMPLETED'
                """).params(customer, key, requestHash)
                .query((rs, i) -> new Stored(rs.getInt("response_status"), rs.getString("response_body")))
                .optional();
    }

    public void complete(UUID customer, String key, int status, String body) {
        jdbc.sql("""
                update idempotency_keys set status = 'COMPLETED', response_status = ?, response_body = ?, completed_at = now()
                where customer_id = ? and key = ?
                """).params(status, body, customer, key).update();
    }

    /** Forget the key so the client can retry it (used for transient failures). */
    public void release(UUID customer, String key) {
        jdbc.sql("delete from idempotency_keys where customer_id = ? and key = ? and status = 'IN_PROGRESS'")
                .params(customer, key).update();
    }

    /** Keys are kept for a day; after that a reused key is simply a new request. */
    public int purgeOlderThan(Duration age) {
        return jdbc.sql("delete from idempotency_keys where created_at < now() - make_interval(secs => ?)")
                .param(age.toSeconds()).update();
    }

    public record Stored(int status, String body) {
    }

    private record Row(String requestHash, String status, Integer responseStatus, String responseBody, boolean abandoned) {
    }
}
