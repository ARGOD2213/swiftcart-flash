package dev.mahindra.swiftcart.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import dev.mahindra.swiftcart.IntegrationTest;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.inventory.InventoryRebuilder;
import dev.mahindra.swiftcart.inventory.Keys;

class ReaperAndRebuildIT extends IntegrationTest {

    @Autowired
    ReservationReaper reaper;
    @Autowired
    InventoryRebuilder rebuilder;
    @Autowired
    AuditService audit;

    @Test
    void unpaidHoldsExpireAndTheirUnitsGoBackOnSale() throws Exception {
        Drop d = liveDrop(1, 2);
        JsonNode o = order(shopper(), d, "TEE-M", 1);
        assertThat(problem(checkout(shopper(), d, UUID.randomUUID().toString(), body("TEE-M", 1, uniquePhone())))).isEqualTo("sold_out");

        expireNow(d, o.path("id").asText());
        assertThat(reaper.reap()).isGreaterThanOrEqualTo(1);

        assertThat(status(o.path("id").asText())).isEqualTo("EXPIRED");
        assertThat(stock(d, "TEE-M")).isEqualTo(1);
        assertThat(checkout(shopper(), d, UUID.randomUUID().toString(), body("TEE-M", 1, uniquePhone())).getResponse().getStatus())
                .isEqualTo(201);
    }

    @Test
    void theDatabaseSafetyNetReleasesHoldsRedisForgotToExpire() throws Exception {
        Drop d = liveDrop(5, 2);
        JsonNode o = order(shopper(), d, "TEE-M", 1);
        UUID reservation = orders.find(UUID.fromString(o.path("id").asText())).orElseThrow().reservationId();
        // Redis lost the expiry entry; the order's hold ended minutes ago
        redis.opsForZSet().remove(Keys.expiry(d.id()), reservation.toString());
        redis.opsForHash().put(Keys.reservation(d.id(), reservation), "exp", String.valueOf(System.currentTimeMillis() - 600_000));
        jdbc.sql("update orders set reserved_until = now() - interval '10 minutes' where id = ?")
                .param(UUID.fromString(o.path("id").asText())).update();

        reaper.reap();

        assertThat(status(o.path("id").asText())).isEqualTo("EXPIRED");
        assertThat(stock(d, "TEE-M")).isEqualTo(5);
    }

    @Test
    void whenRedisLosesADropItIsRebuiltFromTheOrderLogAndLimitsStillHold() throws Exception {
        Drop d = liveDrop(10, 2);
        Shopper buyer = shopper();
        JsonNode paid = order(buyer, d, "TEE-M", 1);
        pay(paid);
        JsonNode pending = order(buyer, d, "TEE-M", 1);
        order(shopper(), d, "TEE-L", 2);

        // Redis failover to a replica that missed the writes, or a flush
        redis.delete(redis.keys("sc:{" + d.id() + "}:*"));
        assertThat(problem(checkout(shopper(), d, UUID.randomUUID().toString(), body("TEE-M", 1, uniquePhone()))))
                .isEqualTo("inventory_unavailable");

        dropAdmin.arm(d.id()); // re-arming an armed drop with missing inventory rebuilds it

        assertThat(stock(d, "TEE-M")).isEqualTo(8);
        assertThat(stock(d, "TEE-L")).isEqualTo(8);
        // the buyer already holds 2 (one paid, one pending): the limit survived the rebuild
        MvcResult third = checkout(buyer, d, UUID.randomUUID().toString(), body("TEE-M", 1, uniquePhone()));
        assertThat(problem(third)).isEqualTo("limit_reached");
        // the pending hold is still a hold: it can be paid, or it expires
        assertThat(pay(pending).outcome().name()).isEqualTo("PAID");
        assertThat(audit.audit(d).ok()).isTrue();
    }

    @Test
    void rebuildingReportsWhatItRestored() throws Exception {
        Drop d = liveDrop(3, 2);
        pay(order(shopper(), d, "TEE-M", 2));
        InventoryRebuilder.Result r = rebuilder.rebuild(d);
        assertThat(r.stock()).containsEntry("TEE-M", 1L).containsEntry("TEE-L", 3L);
        assertThat(r.paidHoldings()).isEqualTo(1);
        assertThat(r.oversoldUnits()).isZero();
    }

    private String problem(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString()).path("code").asText();
    }
}
