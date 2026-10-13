package dev.mahindra.swiftcart.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import dev.mahindra.swiftcart.IntegrationTest;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.inventory.InventoryService.ReserveResult.Code;

/** The Lua inventory against a real Redis, including under heavy concurrency. */
class InventoryIT extends IntegrationTest {

    @Test
    void manyBuyersRacingForFewUnitsNeverOversell() throws Exception {
        Drop d = liveDrop(50, 2);
        List<ReserveOutcome> outcomes = race(300, i -> inventory.reserve(d.id(), "TEE-M", 1, UUID.randomUUID(),
                "+91" + phone(i), UUID.randomUUID()));

        assertThat(outcomes).filteredOn(o -> o.code == Code.OK).hasSize(50);
        assertThat(outcomes).filteredOn(o -> o.code == Code.SOLD_OUT).hasSize(250);
        assertThat(stock(d, "TEE-M")).isZero();
        assertThat(stock(d, "TEE-L")).isEqualTo(50); // other SKUs untouched
    }

    @Test
    void oneCustomerHammeringCheckoutGetsOnlyTheLimit() throws Exception {
        Drop d = liveDrop(100, 2);
        UUID customer = UUID.randomUUID();
        List<ReserveOutcome> outcomes = race(40, i -> inventory.reserve(d.id(), "TEE-M", 1, customer, "+91" + phone(i), UUID.randomUUID()));

        assertThat(outcomes).filteredOn(o -> o.code == Code.OK).hasSize(2);
        assertThat(outcomes).filteredOn(o -> o.code == Code.LIMIT).hasSize(38);
        assertThat(stock(d, "TEE-M")).isEqualTo(98);
    }

    @Test
    void manyAccountsWithOnePhoneShareOneLimit() throws Exception {
        Drop d = liveDrop(100, 2);
        List<ReserveOutcome> outcomes = race(20, i -> inventory.reserve(d.id(), "TEE-M", 1, UUID.randomUUID(), "+919876543210", UUID.randomUUID()));

        assertThat(outcomes).filteredOn(o -> o.code == Code.OK).hasSize(2);
        assertThat(outcomes).filteredOn(o -> o.code == Code.PHONE_LIMIT).hasSize(18);
    }

    @Test
    void askingForMoreThanIsLeftSaysHowManyAreLeft() {
        Drop d = liveDrop(1, 3);
        var r = inventory.reserve(d.id(), "TEE-M", 2, UUID.randomUUID(), "+919000000001", UUID.randomUUID());
        assertThat(r.code()).isEqualTo(Code.INSUFFICIENT);
        assertThat(r.value()).isEqualTo(1);
        assertThat(stock(d, "TEE-M")).isEqualTo(1);
    }

    @Test
    void theSameReservationIdTakesUnitsOnlyOnce() {
        Drop d = liveDrop(10, 2);
        UUID reservation = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        var first = inventory.reserve(d.id(), "TEE-M", 1, customer, "+919000000002", reservation);
        var again = inventory.reserve(d.id(), "TEE-M", 1, customer, "+919000000002", reservation);
        assertThat(first.code()).isEqualTo(Code.OK);
        assertThat(again.code()).isEqualTo(Code.DUPLICATE);
        assertThat(again.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(stock(d, "TEE-M")).isEqualTo(9);
    }

    @Test
    void quantityAboveTheLimitAndUnknownSkusAreRefused() {
        Drop d = liveDrop(10, 2);
        assertThat(inventory.reserve(d.id(), "TEE-M", 3, UUID.randomUUID(), "+919000000003", UUID.randomUUID()).code()).isEqualTo(Code.BAD_QTY);
        assertThat(inventory.reserve(d.id(), "TEE-M", 0, UUID.randomUUID(), "+919000000003", UUID.randomUUID()).code()).isEqualTo(Code.BAD_QTY);
        assertThat(inventory.reserve(d.id(), "NOPE", 1, UUID.randomUUID(), "+919000000003", UUID.randomUUID()).code()).isEqualTo(Code.UNKNOWN_SKU);
    }

    @Test
    void nothingSellsBeforeTheDropOpensOrAfterItCloses() {
        Drop upcoming = drop(Instant.now().plusSeconds(3600), 10, 2, 600, 100);
        assertThat(inventory.reserve(upcoming.id(), "TEE-M", 1, UUID.randomUUID(), "+919000000004", UUID.randomUUID()).code())
                .isEqualTo(Code.NOT_OPEN);
        Drop live = liveDrop(10, 2);
        redis.opsForHash().put(Keys.meta(live.id()), "closes", String.valueOf(System.currentTimeMillis() - 1));
        assertThat(inventory.reserve(live.id(), "TEE-M", 1, UUID.randomUUID(), "+919000000004", UUID.randomUUID()).code())
                .isEqualTo(Code.CLOSED);
    }

    @Test
    void armingAgainNeverResetsLiveStock() {
        Drop d = liveDrop(10, 2);
        inventory.reserve(d.id(), "TEE-M", 2, UUID.randomUUID(), "+919000000005", UUID.randomUUID());
        assertThat(inventory.arm(d)).isFalse();
        dropAdmin.arm(d.id());
        assertThat(stock(d, "TEE-M")).isEqualTo(8);
    }

    @Test
    void releaseIsIdempotentAndNeverTouchesAPaidReservation() {
        Drop d = liveDrop(10, 2);
        UUID customer = UUID.randomUUID();
        UUID held = UUID.randomUUID();
        UUID paid = UUID.randomUUID();
        inventory.reserve(d.id(), "TEE-M", 1, customer, "+919000000006", held);
        inventory.reserve(d.id(), "TEE-M", 1, customer, "+919000000006", paid);
        assertThat(inventory.confirm(d.id(), paid)).isEqualTo(InventoryService.ConfirmResult.CONFIRMED);
        assertThat(inventory.confirm(d.id(), paid)).isEqualTo(InventoryService.ConfirmResult.ALREADY);

        assertThat(inventory.release(d.id(), held, false)).isEqualTo(InventoryService.ReleaseResult.RELEASED);
        assertThat(inventory.release(d.id(), held, false)).isEqualTo(InventoryService.ReleaseResult.MISSING);
        assertThat(inventory.release(d.id(), paid, false)).isEqualTo(InventoryService.ReleaseResult.CONFIRMED);
        assertThat(stock(d, "TEE-M")).isEqualTo(9);
        // the released unit counts against the customer's limit no more
        assertThat(redis.opsForHash().get(Keys.heldByCustomer(d.id()), customer.toString())).isEqualTo("1");
    }

    @Test
    void anUnexpiredHoldIsNotReleasedByTheReaperMode() {
        Drop d = liveDrop(10, 2);
        UUID reservation = UUID.randomUUID();
        inventory.reserve(d.id(), "TEE-M", 1, UUID.randomUUID(), "+919000000007", reservation);
        assertThat(inventory.release(d.id(), reservation, true)).isEqualTo(InventoryService.ReleaseResult.NOT_EXPIRED);
        assertThat(stock(d, "TEE-M")).isEqualTo(9);
    }

    @Test
    void stockReportsSoldOutOnlyWhenNothingCanComeBack() {
        Drop d = liveDrop(1, 2);
        UUID reservation = UUID.randomUUID();
        inventory.reserve(d.id(), "TEE-M", 1, UUID.randomUUID(), "+919000000008", reservation);
        inventory.reserve(d.id(), "TEE-L", 1, UUID.randomUUID(), "+919000000009", UUID.randomUUID());
        assertThat(inventory.stock(d.id()).soldOut()).isFalse(); // two holds could still expire
        assertThat(inventory.stock(d.id()).available()).isEqualTo(Map.of("TEE-M", 0L, "TEE-L", 0L));
    }

    private record ReserveOutcome(Code code) {
    }

    private interface Attempt {
        InventoryService.ReserveResult run(int i) throws Exception;
    }

    private static List<ReserveOutcome> race(int n, Attempt attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReserveOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int index = i;
            Callable<ReserveOutcome> c = () -> {
                start.await();
                return new ReserveOutcome(attempt.run(index).code());
            };
            futures.add(pool.submit(c));
        }
        start.countDown();
        List<ReserveOutcome> out = new ArrayList<>();
        for (Future<ReserveOutcome> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }
}
