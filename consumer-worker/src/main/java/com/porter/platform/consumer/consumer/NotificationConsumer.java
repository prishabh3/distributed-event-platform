package com.porter.platform.consumer.consumer;

import com.porter.platform.contracts.EventMessage;
import com.porter.platform.contracts.EventStatus;
import com.porter.platform.contracts.RetryHeaders;
import com.porter.platform.consumer.metrics.ConsumerMetrics;
import com.porter.platform.consumer.service.DeduplicationService;
import com.porter.platform.consumer.service.RetryRoutingService;
import com.porter.platform.consumer.service.WebhookDispatcher;
import io.micrometer.observation.annotation.Observed;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Main Kafka consumer for the "notifications" topic and retry topic ladder.
 *
 * Offset commit contract:
 *   Offsets are committed (ack.acknowledge()) ONLY after:
 *     1. Webhook delivery confirmed (HTTP 2xx)
 *     2. DB transaction committed (processed_events insert + events status update)
 *
 *   TransactionTemplate is used explicitly so ack.acknowledge() fires post-commit,
 *   not mid-transaction. This prevents committing Kafka offsets before DB writes land.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final WebhookDispatcher webhookDispatcher;
    private final DeduplicationService deduplicationService;
    private final RetryRoutingService retryRoutingService;
    private final ConsumerMetrics metrics;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;

    public NotificationConsumer(WebhookDispatcher webhookDispatcher,
                                DeduplicationService deduplicationService,
                                RetryRoutingService retryRoutingService,
                                ConsumerMetrics metrics,
                                JdbcTemplate jdbcTemplate,
                                PlatformTransactionManager transactionManager) {
        this.webhookDispatcher = webhookDispatcher;
        this.deduplicationService = deduplicationService;
        this.retryRoutingService = retryRoutingService;
        this.metrics = metrics;
        this.jdbcTemplate = jdbcTemplate;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @KafkaListener(
            topics = "notifications",
            groupId = "notification-consumer-group",
            concurrency = "6",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Observed(name = "consumer.notifications")
    public void consume(ConsumerRecord<String, EventMessage> record, Acknowledgment ack) {
        EventMessage message = record.value();
        String eventId = message.eventId();

        log.debug("Received event: id={} type={} partition={} offset={}",
                eventId, message.eventType(), record.partition(), record.offset());

        // Pre-check (no DB connection) — reject known duplicates immediately
        if (deduplicationService.isDuplicate(eventId)) {
            log.info("Skipping duplicate event: {}", eventId);
            metrics.recordDuplicateSkipped();
            ack.acknowledge();
            return;
        }

        try {
            webhookDispatcher.dispatch(message);

            // Commit DB writes in a transaction; ack only after commit succeeds
            Boolean recorded = txTemplate.execute(status -> {
                boolean ok = deduplicationService.markProcessed(eventId);
                if (ok) {
                    updateEventStatus(eventId, EventStatus.DELIVERED, null);
                }
                return ok;
            });

            if (Boolean.FALSE.equals(recorded)) {
                log.info("Concurrent duplicate detected post-dispatch: {}", eventId);
                metrics.recordDuplicateSkipped();
            }

            ack.acknowledge();
            log.info("Event processed successfully: {}", eventId);

        } catch (WebhookDispatcher.NonRetryableWebhookException e) {
            log.warn("Permanent webhook failure (4xx), routing to DLQ: eventId={} reason={}", eventId, e.getMessage());
            retryRoutingService.sendToDlq(message, e.getMessage());
            final String dlqReason = e.getMessage();
            txTemplate.executeWithoutResult(s -> updateEventStatus(eventId, EventStatus.DEAD_LETTERED, dlqReason));
            ack.acknowledge();

        } catch (Exception e) {
            log.warn("Transient failure, routing to retry topic: eventId={} reason={}", eventId, e.getMessage());
            retryRoutingService.routeToRetry(message, e.getMessage());
            final String failReason = e.getMessage();
            txTemplate.executeWithoutResult(s -> updateEventStatus(eventId, EventStatus.FAILED, failReason));
            ack.acknowledge();
        }
    }

    @KafkaListener(
            topics = "notifications.retry.1m",
            groupId = "retry-1m-consumer-group",
            concurrency = "3",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeRetry1m(ConsumerRecord<String, EventMessage> record, Acknowledgment ack) {
        processRetryMessage(record, ack);
    }

    @KafkaListener(
            topics = "notifications.retry.5m",
            groupId = "retry-5m-consumer-group",
            concurrency = "3",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeRetry5m(ConsumerRecord<String, EventMessage> record, Acknowledgment ack) {
        processRetryMessage(record, ack);
    }

    @KafkaListener(
            topics = "notifications.retry.30m",
            groupId = "retry-30m-consumer-group",
            concurrency = "3",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeRetry30m(ConsumerRecord<String, EventMessage> record, Acknowledgment ack) {
        processRetryMessage(record, ack);
    }

    @KafkaListener(
            topics = "notifications.dlq",
            groupId = "dlq-consumer-group",
            concurrency = "1",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeDlq(ConsumerRecord<String, EventMessage> record, Acknowledgment ack) {
        EventMessage message = record.value();
        String failureReason = headerValue(record, RetryHeaders.FAILURE_REASON);
        log.error("DLQ event — permanent failure, no more retries: eventId={} type={} attempts={} reason={}",
                message.eventId(), message.eventType(), message.retryCount(), failureReason);
        metrics.recordDlqEvent();
        ack.acknowledge();
    }

    /**
     * Retry topic consumer.
     * Checks X-Retry-Scheduled-At header — if the delay has not elapsed, nack with a
     * non-blocking partition pause (no Thread.sleep; container resumes the partition
     * automatically after the pause duration).
     */
    private void processRetryMessage(ConsumerRecord<String, EventMessage> record, Acknowledgment ack) {
        EventMessage message = record.value();
        String eventId = message.eventId();

        // Non-blocking delay: pause partition until the scheduled-at time
        String scheduledAtHeader = headerValue(record, RetryHeaders.SCHEDULED_AT);
        if (scheduledAtHeader != null) {
            long scheduledAt = Long.parseLong(scheduledAtHeader);
            long now = System.currentTimeMillis();
            if (now < scheduledAt) {
                long waitMs = scheduledAt - now;
                // Cap at 30s to remain responsive to rebalances; consumer will re-check on resume
                ack.nack(Duration.ofMillis(Math.min(waitMs, 30_000)));
                return;
            }
        }

        if (deduplicationService.isDuplicate(eventId)) {
            log.info("Skipping duplicate retry event: {}", eventId);
            metrics.recordDuplicateSkipped();
            ack.acknowledge();
            return;
        }

        try {
            webhookDispatcher.dispatch(message);

            txTemplate.execute(status -> {
                boolean ok = deduplicationService.markProcessed(eventId);
                if (ok) {
                    updateEventStatus(eventId, EventStatus.DELIVERED, null);
                }
                return ok;
            });

            ack.acknowledge();
            log.info("Retry event processed: eventId={} attempt={}", eventId, message.retryCount());

        } catch (WebhookDispatcher.NonRetryableWebhookException e) {
            log.warn("Permanent failure on retry (4xx): eventId={}", eventId);
            retryRoutingService.sendToDlq(message, e.getMessage());
            final String dlqReason = e.getMessage();
            txTemplate.executeWithoutResult(s -> updateEventStatus(eventId, EventStatus.DEAD_LETTERED, dlqReason));
            ack.acknowledge();

        } catch (Exception e) {
            log.warn("Transient failure on retry attempt={}: eventId={}", message.retryCount(), eventId);
            retryRoutingService.routeToRetry(message, e.getMessage());
            ack.acknowledge();
        }
    }

    private void updateEventStatus(String eventId, EventStatus status, String errorMessage) {
        try {
            if (status == EventStatus.DELIVERED) {
                jdbcTemplate.update(
                        "UPDATE events SET status = ?, delivered_at = ? WHERE id = ?::uuid",
                        status.name(), OffsetDateTime.now(), eventId);
            } else {
                jdbcTemplate.update(
                        "UPDATE events SET status = ?, error_message = ?, retry_count = retry_count + 1 WHERE id = ?::uuid",
                        status.name(), errorMessage, eventId);
            }
        } catch (Exception e) {
            log.warn("Failed to update event status for eventId={}: {}", eventId, e.getMessage());
        }
    }

    private String headerValue(ConsumerRecord<?, ?> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
