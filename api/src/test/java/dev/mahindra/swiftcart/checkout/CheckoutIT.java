package dev.mahindra.swiftcart.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import dev.mahindra.swiftcart.IntegrationTest;
import dev.mahindra.swiftcart.drop.Drop;

class CheckoutIT extends IntegrationTest {

    @Test
    void checkoutReservesUnitsAndOpensAPayment() throws Exception {
        Drop d = liveDrop(10, 2);
        Shopper s = shopper();
        MvcResult r = checkout(s, d, UUID.randomUUID().toString(), body("TEE-M", 2, "98765 43210"));

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JsonNode o = json.readTree(r.getResponse().getContentAsString());
        assertThat(o.path("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(o.path("amountMinor").asLong()).isEqualTo(2 * 149_900);
        assertThat(o.path("payment").path("gateway").asText()).isEqualTo("simulator");
        assertThat(o.path("payment").path("gatewayOrderId").asText()).startsWith("sim_order_");
        assertThat(o.path("reservedUntil").asText()).isNotEmpty();
        assertThat(stock(d, "TEE-M")).isEqualTo(8);
        assertThat(jdbc.sql("select phone from orders where id = ?").param(UUID.fromString(o.path("id").asText()))
                .query(String.class).single()).isEqualTo("+919876543210");
    }

    @Test
    void aRetryWithTheSameKeyReturnsTheSameOrderAndTakesNothingMore() throws Exception {
        Drop d = liveDrop(10, 2);
        Shopper s = shopper();
        String key = UUID.randomUUID().toString();
        String body = body("TEE-M", 1, uniquePhone());
        MvcResult first = checkout(s, d, key, body);
        MvcResult retry = checkout(s, d, key, body);

        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(json.readTree(retry.getResponse().getContentAsString()).path("id"))
                .isEqualTo(json.readTree(first.getResponse().getContentAsString()).path("id"));
        assertThat(stock(d, "TEE-M")).isEqualTo(9);
    }

    @Test
    void aDoubleClickFiringTenRequestsAtOnceCreatesOneOrder() throws Exception {
        Drop d = liveDrop(10, 2);
        Shopper s = shopper();
        String key = UUID.randomUUID().toString();
        String body = body("TEE-M", 1, uniquePhone());
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return checkout(s, d, key, body).getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).contains(201).allMatch(st -> st == 201 || st == 409);
        assertThat(jdbc.sql("select count(*) from orders where customer_id = ?").param(s.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(stock(d, "TEE-M")).isEqualTo(9);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() throws Exception {
        Drop d = liveDrop(10, 2);
        Shopper s = shopper();
        String key = UUID.randomUUID().toString();
        checkout(s, d, key, body("TEE-M", 1, uniquePhone()));
        MvcResult other = checkout(s, d, key, body("TEE-L", 1, uniquePhone()));
        assertThat(other.getResponse().getStatus()).isEqualTo(422);
        assertThat(json.readTree(other.getResponse().getContentAsString()).path("code").asText()).isEqualTo("idempotency_key_reused");
    }

    @Test
    void missingOrMalformedKeysAreRejected() throws Exception {
        Drop d = liveDrop(10, 2);
        Shopper s = shopper();
        mvc.perform(post("/api/drops/" + d.slug() + "/checkout").header("Authorization", "Bearer " + s.token())
                        .header("X-Entry-Token", entryToken(s, d)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("TEE-M", 1, uniquePhone())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("missing_header"));
        assertThat(problemCode(checkout(s, d, "short", body("TEE-M", 1, uniquePhone())))).isEqualTo("invalid_idempotency_key");
        assertThat(problemCode(checkout(s, d, "has spaces in it", body("TEE-M", 1, uniquePhone())))).isEqualTo("invalid_idempotency_key");
    }

    @Test
    void theServerPricesTheOrderWhateverTheClientSends() throws Exception {
        Drop d = liveDrop(10, 2);
        String tampered = """
                {"sku":"TEE-M","quantity":1,"phone":"9876500001","amountMinor":100,"priceMinor":100,"unitPriceMinor":100,
                 "address":{"name":"A","line1":"1 Road","city":"Pune","state":"MH","pincode":"411001"}}
                """;
        JsonNode o = json.readTree(checkout(shopper(), d, UUID.randomUUID().toString(), tampered).getResponse().getContentAsString());
        assertThat(o.path("amountMinor").asLong()).isEqualTo(149_900);
        assertThat(o.path("unitPriceMinor").asLong()).isEqualTo(149_900);
    }

    @Test
    void invalidRequestsSayWhatIsWrong() throws Exception {
        Drop d = liveDrop(10, 2);
        Shopper s = shopper();
        assertThat(problemCode(checkout(s, d, key(), body("NOPE", 1, uniquePhone())))).isEqualTo("unknown_sku");
        assertThat(problemCode(checkout(s, d, key(), body("TEE-M", 3, uniquePhone())))).isEqualTo("quantity_over_limit");
        assertThat(problemCode(checkout(s, d, key(), body("TEE-M", 0, uniquePhone())))).isEqualTo("invalid_request");
        assertThat(problemCode(checkout(s, d, key(), body("TEE-M", 1, "12345")))).isEqualTo("invalid_phone");
        assertThat(problemCode(checkout(s, d, key(), body("TEE-M", 1, "5876543210")))).isEqualTo("invalid_phone");
        String badPin = body("TEE-M", 1, uniquePhone()).replace("560001", "06001");
        MvcResult pin = checkout(s, d, key(), badPin);
        assertThat(problemCode(pin)).isEqualTo("invalid_request");
        assertThat(json.readTree(pin.getResponse().getContentAsString()).path("fields").has("address.pincode")).isTrue();
        assertThat(problemCode(checkout(s, d, key(), "{not json"))).isEqualTo("malformed_json");
        assertThat(stock(d, "TEE-M")).isEqualTo(10);
    }

    @Test
    void theLimitCountsAcrossOrdersAndAcrossAccountsSharingAPhone() throws Exception {
        Drop d = liveDrop(10, 2);
        Shopper s = shopper();
        assertThat(checkout(s, d, key(), body("TEE-M", 1, uniquePhone())).getResponse().getStatus()).isEqualTo(201);
        assertThat(checkout(s, d, key(), body("TEE-L", 1, uniquePhone())).getResponse().getStatus()).isEqualTo(201);
        MvcResult third = checkout(s, d, key(), body("TEE-M", 1, uniquePhone()));
        assertThat(problemCode(third)).isEqualTo("limit_reached");
        assertThat(json.readTree(third.getResponse().getContentAsString()).path("remainingAllowance").asInt()).isZero();

        // the same phone written three ways is one phone
        Shopper x = shopper(), y = shopper(), z = shopper();
        assertThat(checkout(x, d, key(), body("TEE-M", 1, "+91-91234 56789")).getResponse().getStatus()).isEqualTo(201);
        assertThat(checkout(y, d, key(), body("TEE-M", 1, "09123456789")).getResponse().getStatus()).isEqualTo(201);
        assertThat(problemCode(checkout(z, d, key(), body("TEE-M", 1, "919123456789")))).isEqualTo("phone_limit_reached");
    }

    @Test
    void soldOutIsAFinalAnswerForThatKey() throws Exception {
        Drop d = liveDrop(1, 2);
        order(shopper(), d, "TEE-M", 1);
        Shopper late = shopper();
        String key = key();
        String body = body("TEE-M", 1, uniquePhone());
        assertThat(problemCode(checkout(late, d, key, body))).isEqualTo("sold_out");
        MvcResult again = checkout(late, d, key, body);
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(again.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void askingForMoreThanIsLeftSaysHowManyAre() throws Exception {
        Drop d = liveDrop(1, 3);
        MvcResult r = checkout(shopper(), d, key(), body("TEE-M", 2, uniquePhone()));
        assertThat(problemCode(r)).isEqualTo("insufficient_stock");
        assertThat(json.readTree(r.getResponse().getContentAsString()).path("available").asInt()).isEqualTo(1);
    }

    @Test
    void nothingCanBeBoughtBeforeTheDropOpens() throws Exception {
        Drop d = drop(Instant.now().plusSeconds(3600), 10, 2, 600, 100);
        MvcResult r = checkout(shopper(), d, key(), body("TEE-M", 1, uniquePhone()));
        assertThat(problemCode(r)).isEqualTo("drop_not_open");
        assertThat(json.readTree(r.getResponse().getContentAsString()).path("opensAt").asText()).isNotEmpty();
    }

    @Test
    void cancellingGivesUnitsBackOnceAndPaidOrdersCantBeCancelledHere() throws Exception {
        Drop d = liveDrop(10, 2);
        Shopper s = shopper();
        JsonNode o = order(s, d, "TEE-M", 2);
        cancel(s, o).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        cancel(s, o).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stock(d, "TEE-M")).isEqualTo(10);
        // the cancelled units no longer count against the limit
        assertThat(checkout(s, d, key(), body("TEE-M", 2, uniquePhone())).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void ordersArePrivate() throws Exception {
        Drop d = liveDrop(10, 2);
        Shopper owner = shopper();
        JsonNode o = order(owner, d, "TEE-M", 1);
        mvc.perform(get("/api/orders/" + o.path("id").asText()).header("Authorization", "Bearer " + shopper().token()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("order_not_found"));
        mvc.perform(get("/api/orders/" + o.path("id").asText()).header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.payment.gatewayOrderId").isNotEmpty());
        mvc.perform(post("/api/orders/" + o.path("id").asText() + "/cancel").header("Authorization", "Bearer " + shopper().token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPaidOrderCantBeCancelled() throws Exception {
        Drop d = liveDrop(10, 2);
        Shopper s = shopper();
        JsonNode o = order(s, d, "TEE-M", 1);
        pay(o);
        cancel(s, o).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_paid"));
        assertThat(stock(d, "TEE-M")).isEqualTo(9);
    }

    @Test
    void draftsAreInvisibleAndUnknownDropsAre404() throws Exception {
        mvc.perform(get("/api/drops/no-such-drop")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("drop_not_found"));
        Drop draft = dropAdmin.create(new dev.mahindra.swiftcart.drop.DropAdminService.NewDrop("draft-" + UUID.randomUUID().toString().substring(0, 8),
                "Draft", "", null, Instant.now(), Instant.now().plusSeconds(60), Instant.now().plusSeconds(3600), 1, 10, 600, 100,
                List.of(new dev.mahindra.swiftcart.drop.DropAdminService.NewSku("A", "A", 1000, "INR", 1))));
        mvc.perform(get("/api/drops/" + draft.slug())).andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions cancel(Shopper s, JsonNode o) throws Exception {
        return mvc.perform(post("/api/orders/" + o.path("id").asText() + "/cancel").header("Authorization", "Bearer " + s.token()));
    }

    private String problemCode(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString()).path("code").asText();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
