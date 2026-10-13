package dev.mahindra.swiftcart.fulfilment;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.swiftcart.config.SwiftcartProperties;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * Long-polls the fulfilment FIFO queue. A message is deleted only after it was handled (or found to be a
 * duplicate). A message that keeps failing is left alone: after the queue's maxReceiveCount SQS moves it to the
 * dead-letter queue, where an alarm fires. Within a message group (an order), SQS FIFO won't hand out the next
 * message until this one is deleted, so one order's events are processed in order.
 *
 * <p>Runs in the {@code worker} deployment, scaled on queue depth by KEDA (down to zero).
 */
@Component
@ConditionalOnProperty(name = "swiftcart.worker.enabled", havingValue = "true")
public class FulfilmentWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentWorker.class);

    private final SqsClient sqs;
    private final FulfilmentHandler handler;
    private final ObjectMapper json;
    private final String queueUrl;
    private final int maxReceives;
    private ExecutorService executor;
    private volatile boolean running;

    public FulfilmentWorker(SqsClient sqs, FulfilmentHandler handler, ObjectMapper json, SwiftcartProperties props) {
        this.sqs = sqs;
        this.handler = handler;
        this.json = json;
        this.queueUrl = props.aws().fulfilmentQueueUrl();
        this.maxReceives = props.worker().maxReceives();
    }

    @Override
    public void start() {
        running = true;
        executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "fulfilment-worker"));
        executor.submit(this::loop);
        log.info("Fulfilment worker polling {}", queueUrl);
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdown();
        try {
            // finish the message in hand; the long poll returns within 20 s
            executor.awaitTermination(25, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10)
                        .waitTimeSeconds(20).messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
                        .messages();
                for (Message m : messages) {
                    process(m);
                }
            } catch (RuntimeException e) {
                log.warn("Polling failed, backing off: {}", e.getMessage());
                sleep(2000);
            }
        }
    }

    void process(Message m) {
        int receives = Integer.parseInt(m.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"));
        try {
            JsonNode envelope = json.readTree(m.body());
            MDC.put("event_id", envelope.path("eventId").asText());
            boolean handled = handler.handle(envelope);
            sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(m.receiptHandle()));
            log.info("{} event {} ({})", handled ? "Handled" : "Skipped duplicate", envelope.path("eventId").asText(),
                    envelope.path("type").asText());
        } catch (Exception e) {
            // not deleted: SQS redelivers after the visibility timeout, then dead-letters it after maxReceiveCount
            log.error("Event failed (receive {} of {}): {}", receives, maxReceives, e.getMessage(), e);
        } finally {
            MDC.remove("event_id");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
