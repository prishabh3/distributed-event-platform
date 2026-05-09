package com.porter.platform.consumer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", columnDefinition = "uuid")
    private UUID eventId;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    public ProcessedEvent() {}

    public ProcessedEvent(UUID eventId) {
        this.eventId = eventId;
        this.processedAt = OffsetDateTime.now();
    }

    public UUID getEventId() { return eventId; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
}
