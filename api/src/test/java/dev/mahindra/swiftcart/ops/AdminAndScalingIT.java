package dev.mahindra.swiftcart.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

import dev.mahindra.swiftcart.IntegrationTest;
import dev.mahindra.swiftcart.drop.Drop;

class AdminAndScalingIT extends IntegrationTest {

    @Autowired
    AuditService audit;

    @Test
    void anAdminCreatesArmsAndAuditsADrop() throws Exception {
        Shopper admin = admin();
        String slug = "admin-" + UUID.randomUUID().toString().substring(0, 8);
        Instant opens = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
        String body = """
                {"slug":"%s","name":"Admin drop","queueOpensAt":"%s","opensAt":"%s","closesAt":"%s",
                 "perCustomerLimit":1,"admitPerSecond":50,"reservationTtlSeconds":300,"expectedPeakRps":900,
                 "skus":[{"code":"CAP-1","name":"Cap","priceMinor":99900,"initialStock":5}]}
                """.formatted(slug, opens.minusSeconds(60), opens, opens.plusSeconds(3600));
        JsonNode created = json.readTree(mvc.perform(post("/api/admin/drops").header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString());
        String id = created.path("id").asText();

        mvc.perform(post("/api/admin/drops").header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("slug_taken"));
        mvc.perform(post("/api/admin/drops/" + id + "/arm").header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARMED"));
        mvc.perform(get("/api/drops/" + slug + "/stock"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available['CAP-1']").value(5));
        mvc.perform(get("/api/admin/drops/" + id + "/audit").header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void badDropDefinitionsAreRejected() throws Exception {
        Shopper admin = admin();
        Instant t = Instant.now();
        String closesBeforeOpens = """
                {"slug":"bad-%s","name":"x","queueOpensAt":"%s","opensAt":"%s","closesAt":"%s","perCustomerLimit":1,
                 "admitPerSecond":5,"skus":[{"code":"A","name":"a","priceMinor":1000,"initialStock":1}]}
                """.formatted(UUID.randomUUID().toString().substring(0, 6), t, t.plusSeconds(60), t);
        mvc.perform(post("/api/admin/drops").header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content(closesBeforeOpens))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_times"));
        String duplicateSku = closesBeforeOpens.replace("\"closesAt\":\"" + t + "\"", "\"closesAt\":\"" + t.plusSeconds(600) + "\"")
                .replace("[{\"code\":\"A\",\"name\":\"a\",\"priceMinor\":1000,\"initialStock\":1}]",
                        "[{\"code\":\"A\",\"name\":\"a\",\"priceMinor\":1000,\"initialStock\":1},{\"code\":\"A\",\"name\":\"b\",\"priceMinor\":1000,\"initialStock\":1}]");
        mvc.perform(post("/api/admin/drops").header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content(duplicateSku))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("duplicate_sku"));
        String badSlug = duplicateSku.replace("\"slug\":\"bad-", "\"slug\":\"Bad Slug ");
        mvc.perform(post("/api/admin/drops").header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content(badSlug))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void checkoutPodsArePreScaledBeforeADropAndOnlyThen() throws Exception {
        drop(Instant.now().plusSeconds(600), 10, 2, 600, 100); // opens in 10 minutes, expects 300 rps
        mvc.perform(get("/internal/scaling")).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutReplicas").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("expects")));
    }

    @Test
    void theAuditCatchesAnOversell() throws Exception {
        Drop d = liveDrop(1, 2);
        pay(order(shopper(), d, "TEE-M", 1));
        assertThat(audit.audit(d).ok()).isTrue();
        // simulate a bug that sold a unit twice
        jdbc.sql("""
                insert into orders (id, drop_id, sku_id, customer_id, quantity, unit_price_minor, amount_minor, currency, phone,
                                    shipping_address, status, reservation_id, reserved_until, idempotency_key, gateway)
                select gen_random_uuid(), drop_id, sku_id, customer_id, 1, unit_price_minor, amount_minor, currency, '+919999999999',
                       shipping_address, 'PAID', gen_random_uuid(), reserved_until, 'bug', gateway
                from orders where drop_id = ? limit 1
                """).param(d.id()).update();
        AuditService.Report r = audit.audit(d);
        assertThat(r.ok()).isFalse();
        assertThat(r.skus()).anySatisfy(s -> assertThat(s.oversold()).isTrue());
    }
}
