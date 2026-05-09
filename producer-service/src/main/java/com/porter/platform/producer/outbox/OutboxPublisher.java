package com.porter.platform.producer.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.porter.platform.contracts.EventMessage;
import com.porter.platform.producer.domain.OutboxEvent;
import com.porter.platform.producer.metrics.EventMetrics;
import com.porter.platform.producer.repository.OutboxEventRepository;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls the outbox table and relays pending events to Kafka.
 *
 * Transaction design — two short, independent transactions per batch:
 *
 *   TX-1 (REQUIRES_NEW):  SELECT ... FOR UPDATE SKIP LOCKED → claim batch rows
 *                         Commits immediately, releasing row locks.
 *
 *   Per-event Kafka send: outside any transaction (no DB connection held).
 *
 *   TX-2 (REQUIRES_NEW):  UPDATE outbox_events SET published=true → one row per event.
 *                         Independent so partial success is preserved on partial failure.
 *
 * TransactionTemplate is used instead of @Transactional because these methods are called
 * via `this` reference from within the same bean — Spring AOP proxies do not intercept
 * self-invocations, so @Transactional annotations on private/same-class methods are silently
 * ignored. TransactionTemplate bypasses this limitation entirely.
 *
 * Kafka producer idempotence (enable.idempotence=true) + consumer-side deduplication mean
 * that any duplicate publishes from concurrent OutboxPublisher instances are safe.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, EventMessage> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final EventMetrics metrics;
    private final int batchSize;
    private final long kafkaSendTimeoutSeconds;
    private final TransactionTemplate requiresNewTx;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           KafkaTemplate<String, EventMessage> kafkaTemplate,
                           ObjectMapper objectMapper,
                           EventMetrics metrics,
                           PlatformTransactionManager transactionManager,
                           @Value("${app.outbox.batch-size:100}") int batchSize,
                           @Value("${app.outbox.kafka-send-timeout-seconds:5}") long kafkaSendTimeoutSeconds) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.kafkaSendTimeoutSeconds = kafkaSendTimeoutSeconds;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:100}")
    @Observed(name = "outbox.publish")
    public void publishPendingEvents() {
        // TX-1: claim a batch with SELECT FOR UPDATE SKIP LOCKED.
        // Runs inside a real transaction so the lock is actually acquired;
        // commits immediately upon return so DB connection is freed before Kafka I/O.
        List<OutboxEvent> batch = requiresNewTx.execute(
                status -> outboxEventRepository.findUnpublishedBatchForUpdate(batchSize)
        );

        if (batch == null || batch.isEmpty()) {
            return;
        }

        log.debug("Publishing outbox batch of {} events", batch.size());
        int published = 0;

        for (OutboxEvent outboxEvent : batch) {
            long startNanos = System.nanoTime();
            try {
                EventMessage message = objectMapper.readValue(outboxEvent.getPayload(), EventMessage.class);

                // Blocking send with ack timeout.
                // Guarantees Kafka has acknowledged before we mark the row published.
                kafkaTemplate.send(outboxEvent.getTopic(), outboxEvent.getPartitionKey(), message)
                        .get(kafkaSendTimeoutSeconds, TimeUnit.SECONDS);

                // TX-2: mark this one row published in its own short transaction.
                // Independent commits mean other rows are not rolled back on a single failure.
                final Long eventDbId = outboxEvent.getId();
                requiresNewTx.executeWithoutResult(
                        status -> outboxEventRepository.markAsPublished(eventDbId, OffsetDateTime.now())
                );

                metrics.recordEventDelivered();
                metrics.recordOutboxPublishLatency(System.nanoTime() - startNanos);
                published++;

            } catch (Exception e) {
                log.error("Failed to publish outbox event id={} eventId={}: {}",
                        outboxEvent.getId(), outboxEvent.getEventId(), e.getMessage());
                metrics.recordEventFailed();
                // Row remains published=false → picked up on next poll cycle
            }
        }

        if (published > 0) {
            log.info("Published {}/{} outbox events to Kafka", published, batch.size());
        }
    }
}
