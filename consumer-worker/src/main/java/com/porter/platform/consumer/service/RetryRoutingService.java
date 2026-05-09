package com.porter.platform.consumer.service;

import com.porter.platform.contracts.EventMessage;
import com.porter.platform.contracts.RetryHeaders;
import com.porter.platform.consumer.metrics.ConsumerMetrics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Routes failed messages through the retry topic ladder:
 *
 *   notifications → notifications.retry.1m → notifications.retry.5m
 *                → notifications.retry.30m → notifications.dlq
 *
 * Retry count is embedded in the EventMessage record (incremented on each routing).
 * Scheduled-at timestamp is stored in Kafka header X-Retry-Scheduled-At so the
 * consuming retry listener can apply a non-blocking partition pause until ready.
 */
@Service
public class RetryRoutingService {

    private static final Logger log = LoggerFactory.getLogger(RetryRoutingService.class);

    private static final String TOPIC_RETRY_1M  = "notifications.retry.1m";
    private static final String TOPIC_RETRY_5M  = "notifications.retry.5m";
    private static final String TOPIC_RETRY_30M = "notifications.retry.30m";
    private static final String TOPIC_DLQ       = "notifications.dlq";

    private final KafkaTemplate<String, EventMessage> kafkaTemplate;
    private final ConsumerMetrics metrics;
    private final int maxAttempts;
    private final long delay1mMs;
    private final long delay5mMs;
    private final long delay30mMs;

    public RetryRoutingService(
            KafkaTemplate<String, EventMessage> retryKafkaTemplate,
            ConsumerMetrics metrics,
            @org.springframework.beans.factory.annotation.Value("${app.retry.max-attempts:3}") int maxAttempts,
            @org.springframework.beans.factory.annotation.Value("${app.retry.delay-1m-ms:60000}") long delay1mMs,
            @org.springframework.beans.factory.annotation.Value("${app.retry.delay-5m-ms:300000}") long delay5mMs,
            @org.springframework.beans.factory.annotation.Value("${app.retry.delay-30m-ms:1800000}") long delay30mMs) {
        this.kafkaTemplate = retryKafkaTemplate;
        this.metrics = metrics;
        this.maxAttempts = maxAttempts;
        this.delay1mMs = delay1mMs;
        this.delay5mMs = delay5mMs;
        this.delay30mMs = delay30mMs;
    }

    /**
     * Routes to the appropriate retry topic or DLQ based on retryCount.
     */
    public void routeToRetry(EventMessage message, String failureReason) {
        int retryCount = message.retryCount();

        if (retryCount >= maxAttempts) {
            sendToDlq(message, failureReason);
            return;
        }

        String nextTopic;
        long delayMs;

        switch (retryCount) {
            case 0 -> { nextTopic = TOPIC_RETRY_1M;  delayMs = delay1mMs; }
            case 1 -> { nextTopic = TOPIC_RETRY_5M;  delayMs = delay5mMs; }
            default -> { nextTopic = TOPIC_RETRY_30M; delayMs = delay30mMs; }
        }

        EventMessage retryMessage = message.withIncrementedRetry();
        long scheduledAt = Instant.now().toEpochMilli() + delayMs;

        ProducerRecord<String, EventMessage> record = new ProducerRecord<>(
                nextTopic, null, message.partitionKey(), retryMessage);
        record.headers().add(new RecordHeader(
                RetryHeaders.SCHEDULED_AT,
                String.valueOf(scheduledAt).getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(
                RetryHeaders.FAILURE_REASON,
                failureReason.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(
                RetryHeaders.ORIGINAL_TOPIC,
                "notifications".getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(record);
        metrics.recordRetryAttempt(retryCount + 1);

        log.info("Routed eventId={} to {} (retry={}), scheduledAt={}",
                message.eventId(), nextTopic, retryCount + 1, scheduledAt);
    }

    public void sendToDlq(EventMessage message, String failureReason) {
        ProducerRecord<String, EventMessage> record = new ProducerRecord<>(
                TOPIC_DLQ, null, message.partitionKey(), message);
        record.headers().add(new RecordHeader(
                RetryHeaders.FAILURE_REASON,
                failureReason.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(
                RetryHeaders.FAILURE_TIMESTAMP,
                String.valueOf(Instant.now().toEpochMilli()).getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(record);
        metrics.recordDlqEvent();

        log.warn("Event sent to DLQ: eventId={} reason={}", message.eventId(), failureReason);
    }
}
