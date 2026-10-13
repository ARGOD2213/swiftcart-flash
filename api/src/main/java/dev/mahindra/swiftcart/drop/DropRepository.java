package dev.mahindra.swiftcart.drop;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import dev.mahindra.swiftcart.config.Db;

@Repository
public class DropRepository {

    private final JdbcClient jdbc;

    public DropRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Drop> findBySlug(String slug) {
        return jdbc.sql("select * from drops where slug = ?").param(slug).query(this::dropRow).optional().map(this::withSkus);
    }

    public Optional<Drop> findById(UUID id) {
        return jdbc.sql("select * from drops where id = ?").param(id).query(this::dropRow).optional().map(this::withSkus);
    }

    /** Drops shoppers can see: armed or settled, newest opening first. */
    public List<Drop> findVisible() {
        return jdbc.sql("select * from drops where status in ('ARMED', 'SETTLED') order by opens_at desc limit 50")
                .query(this::dropRow).list().stream().map(this::withSkus).toList();
    }

    /** Armed drops whose reservations can still be live: anything not closed for longer than its TTL. */
    public List<Drop> findActive(Instant now) {
        return jdbc.sql("""
                select * from drops
                where status = 'ARMED' and closes_at + make_interval(secs => reservation_ttl_seconds) + interval '5 minutes' > ?
                """).param(Db.ts(now)).query(this::dropRow).list().stream().map(this::withSkus).toList();
    }

    @Transactional
    public Drop insert(Drop d) {
        jdbc.sql("""
                insert into drops (id, slug, name, description, image_url, queue_opens_at, opens_at, closes_at,
                                   per_customer_limit, admit_per_second, reservation_ttl_seconds, expected_peak_rps, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                """)
                .params(d.id(), d.slug(), d.name(), d.description(), d.imageUrl(), Db.ts(d.queueOpensAt()), Db.ts(d.opensAt()),
                        Db.ts(d.closesAt()), d.perCustomerLimit(), d.admitPerSecond(), d.reservationTtlSeconds(), d.expectedPeakRps())
                .update();
        for (Drop.Sku s : d.skus()) {
            jdbc.sql("insert into skus (id, drop_id, code, name, price_minor, currency, initial_stock) values (?, ?, ?, ?, ?, ?, ?)")
                    .params(s.id(), d.id(), s.code(), s.name(), s.priceMinor(), s.currency(), s.initialStock())
                    .update();
        }
        return findById(d.id()).orElseThrow();
    }

    /** Compare-and-set on status, so two admins arming at once can't both win. */
    public boolean transition(UUID id, String from, String to) {
        return jdbc.sql("update drops set status = ? where id = ? and status = ?").params(to, id, from).update() == 1;
    }

    private Drop dropRow(ResultSet rs, int i) throws SQLException {
        return new Drop(Db.uuid(rs, "id"), rs.getString("slug"), rs.getString("name"), rs.getString("description"),
                rs.getString("image_url"), Db.instant(rs, "queue_opens_at"), Db.instant(rs, "opens_at"),
                Db.instant(rs, "closes_at"), rs.getInt("per_customer_limit"), rs.getInt("admit_per_second"),
                rs.getInt("reservation_ttl_seconds"), rs.getInt("expected_peak_rps"), rs.getString("status"), List.of());
    }

    private Drop withSkus(Drop d) {
        List<Drop.Sku> skus = new ArrayList<>(jdbc.sql("select * from skus where drop_id = ? order by code").param(d.id())
                .query((rs, i) -> new Drop.Sku(Db.uuid(rs, "id"), Db.uuid(rs, "drop_id"), rs.getString("code"),
                        rs.getString("name"), rs.getLong("price_minor"), rs.getString("currency"), rs.getInt("initial_stock")))
                .list());
        return new Drop(d.id(), d.slug(), d.name(), d.description(), d.imageUrl(), d.queueOpensAt(), d.opensAt(),
                d.closesAt(), d.perCustomerLimit(), d.admitPerSecond(), d.reservationTtlSeconds(), d.expectedPeakRps(),
                d.status(), List.copyOf(skus));
    }
}
