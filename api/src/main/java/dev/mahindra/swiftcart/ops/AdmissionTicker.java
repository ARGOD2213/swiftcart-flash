package dev.mahindra.swiftcart.ops;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.drop.DropRepository;
import dev.mahindra.swiftcart.queue.WaitingRoomService;

/** Lets the next batch of the waiting room through, once a second, on one pod. */
@Component
@ConditionalOnProperty(name = "swiftcart.jobs.enabled", havingValue = "true")
public class AdmissionTicker {

    private static final Logger log = LoggerFactory.getLogger(AdmissionTicker.class);

    private final DropRepository drops;
    private final WaitingRoomService room;
    private final Locks locks;
    private final Clock clock;

    public AdmissionTicker(DropRepository drops, WaitingRoomService room, Locks locks, Clock clock) {
        this.drops = drops;
        this.room = room;
        this.locks = locks;
        this.clock = clock;
    }

    @Scheduled(fixedRateString = "${swiftcart.jobs.admission-interval-ms:1000}", initialDelay = 1000)
    public void scheduled() {
        try {
            locks.asLeader("admissions", this::tick, 0L);
        } catch (RuntimeException e) {
            log.warn("Admission round failed: {}", e.getMessage());
        }
    }

    public long tick() {
        Instant now = clock.instant();
        long admitted = 0;
        for (Drop drop : drops.findActive(now)) {
            if (drop.phase(now) != Drop.Phase.LIVE) {
                continue;
            }
            try {
                admitted += room.admitNext(drop);
            } catch (RuntimeException e) {
                log.warn("Admissions skipped drop {}: {}", drop.slug(), e.getMessage());
            }
        }
        return admitted;
    }
}
