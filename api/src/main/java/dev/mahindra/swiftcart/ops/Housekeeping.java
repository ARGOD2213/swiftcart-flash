package dev.mahindra.swiftcart.ops;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.mahindra.swiftcart.checkout.IdempotencyService;

/** Idempotency keys are honoured for a day; older ones are removed. */
@Component
@ConditionalOnProperty(name = "swiftcart.jobs.enabled", havingValue = "true")
class Housekeeping {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);

    private final IdempotencyService idempotency;
    private final Locks locks;

    Housekeeping(IdempotencyService idempotency, Locks locks) {
        this.idempotency = idempotency;
        this.locks = locks;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    void purge() {
        try {
            int removed = locks.asLeader("housekeeping", () -> idempotency.purgeOlderThan(Duration.ofHours(24)), 0);
            if (removed > 0) {
                log.info("Removed {} idempotency keys older than a day", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Housekeeping failed: {}", e.getMessage());
        }
    }
}
