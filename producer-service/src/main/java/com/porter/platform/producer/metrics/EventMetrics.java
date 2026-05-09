package com.porter.platform.producer.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class EventMetrics {

    private final Counter eventsReceived;
    private final Counter eventsDelivered;
    private final Counter eventsFailed;
    private final Counter duplicatesRejected;
    private final Counter outboxPublished;
    private final Timer outboxPublishLatency;

    public EventMetrics(MeterRegistry registry) {
        this.eventsReceived = Counter.builder("events_received_total")
                .description("Total number of events accepted by the producer API")
                .register(registry);

        this.eventsDelivered = Counter.builder("events_delivered_total")
                .description("Total number of events successfully published to Kafka via outbox")
                .register(registry);

        this.eventsFailed = Counter.builder("events_failed_total")
                .description("Total number of events that failed during outbox publishing")
                .register(registry);

        this.duplicatesRejected = Counter.builder("events_duplicates_total")
                .description("Total number of duplicate events rejected by idempotency check")
                .register(registry);

        this.outboxPublished = Counter.builder("outbox_published_total")
                .description("Total outbox rows successfully published to Kafka")
                .register(registry);

        this.outboxPublishLatency = Timer.builder("outbox_publish_latency_ms")
                .description("Latency of the outbox-to-Kafka publish operation")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void recordEventReceived() {
        eventsReceived.increment();
    }

    public void recordEventDelivered() {
        eventsDelivered.increment();
        outboxPublished.increment();
    }

    public void recordEventFailed() {
        eventsFailed.increment();
    }

    public void recordDuplicate() {
        duplicatesRejected.increment();
    }

    public void recordOutboxPublishLatency(long nanos) {
        outboxPublishLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public long getEventsReceivedCount() {
        return (long) eventsReceived.count();
    }

    public long getEventsDeliveredCount() {
        return (long) eventsDelivered.count();
    }

    public long getEventsFailedCount() {
        return (long) eventsFailed.count();
    }

    public long getDuplicatesRejectedCount() {
        return (long) duplicatesRejected.count();
    }
}
