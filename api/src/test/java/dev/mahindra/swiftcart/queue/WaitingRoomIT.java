package dev.mahindra.swiftcart.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

import dev.mahindra.swiftcart.IntegrationTest;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.ops.AdmissionTicker;

class WaitingRoomIT extends IntegrationTest {

    @Autowired
    AdmissionTicker ticker;
    @Autowired
    WaitingRoomService room;

    @Test
    void shoppersAreAdmittedInOrderAtTheAdmitRate() throws Exception {
        Drop d = drop(Instant.now().minusSeconds(5), 100, 2, 600, 2);
        Shopper a = shopper(), b = shopper(), c = shopper();
        join(a, d).andExpect(jsonPath("$.ticket").value(1));
        join(b, d).andExpect(jsonPath("$.ticket").value(2));
        join(c, d).andExpect(jsonPath("$.state").value("WAITING")).andExpect(jsonPath("$.aheadOfYou").value(2));

        ticker.tick();

        me(a, d).andExpect(jsonPath("$.state").value("ADMITTED")).andExpect(jsonPath("$.entryToken").isNotEmpty());
        me(b, d).andExpect(jsonPath("$.state").value("ADMITTED"));
        me(c, d).andExpect(jsonPath("$.state").value("WAITING")).andExpect(jsonPath("$.aheadOfYou").value(0));
        ticker.tick();
        me(c, d).andExpect(jsonPath("$.state").value("ADMITTED"));
    }

    @Test
    void rejoiningKeepsYourPlaceInLine() throws Exception {
        Drop d = drop(Instant.now().minusSeconds(5), 100, 2, 600, 1);
        Shopper a = shopper(), b = shopper();
        join(a, d);
        join(b, d);
        join(a, d).andExpect(jsonPath("$.ticket").value(1));
        join(b, d).andExpect(jsonPath("$.ticket").value(2));
        assertThat(room.stats(d).joined()).isEqualTo(2);
    }

    @Test
    void theLineFormsBeforeTheDropButNobodyIsLetInEarly() throws Exception {
        Drop d = drop(Instant.now().plusSeconds(120), 100, 2, 600, 100);
        Shopper a = shopper();
        join(a, d).andExpect(jsonPath("$.state").value("WAITING"));
        ticker.tick();
        me(a, d).andExpect(jsonPath("$.state").value("WAITING"))
                .andExpect(jsonPath("$.estimatedWaitSeconds").value(org.hamcrest.Matchers.greaterThan(100)));
    }

    @Test
    void theWaitingRoomIsClosedUntilItOpens() throws Exception {
        Drop d = drop(Instant.now().plusSeconds(3600), 100, 2, 600, 100);
        mvc.perform(post("/api/drops/" + d.slug() + "/queue").header("Authorization", "Bearer " + shopper().token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("queue_not_open"))
                .andExpect(jsonPath("$.queueOpensAt").exists());
    }

    @Test
    void pausedAdmissionsLetNobodyThrough() throws Exception {
        Drop d = drop(Instant.now().minusSeconds(5), 100, 2, 600, 100);
        Shopper a = shopper();
        join(a, d);
        room.pause(d);
        ticker.tick();
        me(a, d).andExpect(jsonPath("$.state").value("WAITING"));
        room.resume(d);
        ticker.tick();
        me(a, d).andExpect(jsonPath("$.state").value("ADMITTED"));
    }

    @Test
    void aSoldOutDropTurnsNewShoppersAwayAndTellsTheLine() throws Exception {
        Drop d = liveDrop(1, 2);
        Shopper waiting = shopper();
        join(waiting, d);
        JsonNode o1 = order(shopper(), d, "TEE-M", 1);
        JsonNode o2 = order(shopper(), d, "TEE-L", 1);
        pay(o1);
        pay(o2);

        mvc.perform(post("/api/drops/" + d.slug() + "/queue").header("Authorization", "Bearer " + shopper().token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("sold_out"));
        me(waiting, d).andExpect(jsonPath("$.state").value("SOLD_OUT"));
        mvc.perform(get("/api/drops/" + d.slug() + "/stock")).andExpect(jsonPath("$.state").value("SOLD_OUT"));
    }

    @Test
    void checkoutRefusesShoppersWhoSkippedTheLine() throws Exception {
        Drop d = liveDrop(10, 2);
        Drop other = liveDrop(10, 2);
        Shopper s = shopper();
        Shopper someoneElse = shopper();
        String body = body("TEE-M", 1, uniquePhone());

        checkoutWithToken(s, d, null, body).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("entry_token_missing"));
        checkoutWithToken(s, d, "not-a-token", body).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("entry_token_invalid"));
        checkoutWithToken(s, d, entryToken(someoneElse, d), body).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("entry_token_mismatch"));
        checkoutWithToken(s, d, entryToken(s, other), body).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("entry_token_mismatch"));
        // an access token is not an entry token (different key and type)
        checkoutWithToken(s, d, s.token(), body).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions join(Shopper s, Drop d) throws Exception {
        return mvc.perform(post("/api/drops/" + d.slug() + "/queue").header("Authorization", "Bearer " + s.token()))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions me(Shopper s, Drop d) throws Exception {
        return mvc.perform(get("/api/drops/" + d.slug() + "/queue/me").header("Authorization", "Bearer " + s.token()))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions checkoutWithToken(Shopper s, Drop d, String token, String body)
            throws Exception {
        var req = post("/api/drops/" + d.slug() + "/checkout")
                .header("Authorization", "Bearer " + s.token())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (token != null) {
            req.header("X-Entry-Token", token);
        }
        return mvc.perform(req);
    }
}
