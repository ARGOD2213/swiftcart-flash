package dev.mahindra.swiftcart.outbox;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.swiftcart.config.Db;

/**
 * Events are inserted in the same transaction as the state change they describe: an order is never PAID without
 * its ORDER_PAID event, and an event never exists for a change that rolled back.
 */
@Repository
public class OutboxRepository {

    public static final String ORDER_PAID = "ORDER_PAID";
    public static final String REFUND_REQUESTED = "REFUND_REQUESTED";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public OutboxRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID add(String groupKey, String type, Map<String, ?> payload) {
        UUID eventId = UUID.randomUUID();
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        jdbc.sql("insert into outbox (event_id, group_key, type, payload) values (?, ?, ?, ?)")
                .params(eventId, groupKey, type, body).update();
        return eventId;
    }

    /** Oldest unpublished events that are due, in insert order (which is the order within each group). */
    public List<Pending> due(int limit) {
        return jdbc.sql("""
                select id, event_id, group_key, type, payload, attempts from outbox
                where published_at is null and next_attempt_at <= now()
                order by id limit ?
                """).param(limit)
                .query((rs, i) -> new Pending(rs.getLong("id"), Db.uuid(rs, "event_id"), rs.getString("group_key"),
                        rs.getString("type"), rs.getString("payload"), rs.getInt("attempts")))
                .list();
    }

    public void published(long id) {
        jdbc.sql("update outbox set published_at = now() where id = ?").param(id).update();
    }

    /** Exponential backoff, capped at five minutes. */
    public void failed(long id, int attempts, String error) {
        long delaySeconds = Math.min(300, (long) Math.pow(2, Math.min(attempts, 8)));
        jdbc.sql("""
                update outbox set attempts = attempts + 1, last_error = ?,
                                  next_attempt_at = now() + make_interval(secs => ?)
                where id = ?
                """).params(error == null ? "unknown" : error.substring(0, Math.min(500, error.length())), delaySeconds, id).update();
    }

    public long backlog() {
        Long n = jdbc.sql("select count(*) from outbox where published_at is null").query(Long.class).single();
        return n == null ? 0 : n;
    }

    public record Pending(long id, UUID eventId, String groupKey, String type, String payload, int attempts) {
    }
}
