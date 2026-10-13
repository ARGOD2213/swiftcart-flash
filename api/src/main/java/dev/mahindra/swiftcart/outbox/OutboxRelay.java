package dev.mahindra.swiftcart.outbox;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.swiftcart.config.SwiftcartProperties;
import dev.mahindra.swiftcart.ops.Locks;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Publishes outbox events to the SQS FIFO queue, oldest first, from one pod at a time (a single relay keeps
 * per-order order). Message group = order id, so fulfilment sees one order's events in order; deduplication
 * id = event id, so a relay that crashes after sending but before marking doesn't double-deliver within SQS's
 * five-minute window (and consumers de-duplicate anyway).
 *
 * <p>If an event can't be sent, later events of the same group wait for it.
 */
@Component
@ConditionalOnProperty(name = "swiftcart.jobs.enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final SqsClient sqs;
    private final Locks locks;
    private final ObjectMapper json;
    private final String queueUrl;

    public OutboxRelay(OutboxRepository outbox, SqsClient sqs, Locks locks, ObjectMapper json, SwiftcartProperties props) {
        this.outbox = outbox;
        this.sqs = sqs;
        this.locks = locks;
        this.json = json;
        this.queueUrl = props.aws().fulfilmentQueueUrl();
    }

    @Scheduled(fixedDelayString = "${swiftcart.jobs.outbox-interval-ms:250}", initialDelay = 3000)
    public void scheduled() {
        if (!StringUtils.hasText(queueUrl)) {
            return;
        }
        try {
            locks.asLeader("outbox", this::relay, 0);
        } catch (RuntimeException e) {
            log.warn("Outbox relay round failed: {}", e.getMessage());
        }
    }

    public int relay() {
        List<OutboxRepository.Pending> due = outbox.due(50);
        Set<String> blockedGroups = new HashSet<>();
        int sent = 0;
        for (OutboxRepository.Pending e : due) {
            if (blockedGroups.contains(e.groupKey())) {
                continue;
            }
            try {
                sqs.sendMessage(m -> m.queueUrl(queueUrl)
                        .messageGroupId(e.groupKey())
                        .messageDeduplicationId(e.eventId().toString())
                        .messageAttributes(Map.of("type", MessageAttributeValue.builder().dataType("String").stringValue(e.type()).build()))
                        .messageBody(envelope(e)));
                outbox.published(e.id());
                sent++;
            } catch (RuntimeException ex) {
                blockedGroups.add(e.groupKey());
                outbox.failed(e.id(), e.attempts(), ex.getMessage());
                log.warn("Could not publish event {} ({}): {}", e.eventId(), e.type(), ex.getMessage());
            }
        }
        return sent;
    }

    private String envelope(OutboxRepository.Pending e) {
        try {
            return json.writeValueAsString(Map.of(
                    "eventId", e.eventId().toString(),
                    "type", e.type(),
                    "payload", json.readTree(e.payload())));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
