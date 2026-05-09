package com.porter.platform.consumer.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ConsumerMetrics {

    private final Counter eventsDelivered;
    private final Counter eventsFailed;
    private final Counter retryAttempts;
    private final Counter dlqEvents;
    private final Counter duplicatesSkipped;
    private final Timer webhookLatency;

    public ConsumerMetrics(MeterRegistry registry) {
        this.eventsDelivered = Counter.builder("events_delivered_total")
                .description("Events successfully delivered via webhook")
                .register(registry);

        this.eventsFailed = Counter.builder("events_failed_total")
                .description("Events that failed webhook delivery")
                .register(registry);

        this.retryAttempts = Counter.builder("retry_attempts_total")
                .description("Total retry attempts across all retry topics")
                .register(registry);

        this.dlqEvents = Counter.builder("dlq_events_total")
                .description("Events permanently failed and sent to DLQ")
                .register(registry);

        this.duplicatesSkipped = Counter.builder("consumer_duplicates_skipped_total")
                .description("Events skipped due to consumer-side deduplication")
                .register(registry);

        this.webhookLatency = Timer.builder("webhook_latency_ms")
                .description("End-to-end webhook HTTP call latency in milliseconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void recordDeliverySuccess() { eventsDelivered.increment(); }
    public void recordDeliveryFailure() { eventsFailed.increment(); }
    public void recordRetryAttempt(int attempt) { retryAttempts.increment(); }
    public void recordDlqEvent() { dlqEvents.increment(); }
    public void recordDuplicateSkipped() { duplicatesSkipped.increment(); }

    public void recordWebhookLatency(long latencyMs) {
        webhookLatency.record(latencyMs, TimeUnit.MILLISECONDS);
    }
}
