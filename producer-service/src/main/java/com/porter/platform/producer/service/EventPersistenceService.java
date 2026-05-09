package com.porter.platform.producer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.porter.platform.contracts.EventMessage;
import com.porter.platform.contracts.EventStatus;
import com.porter.platform.producer.domain.Event;
import com.porter.platform.producer.domain.OutboxEvent;
import com.porter.platform.producer.dto.EventRequest;
import com.porter.platform.producer.repository.EventRepository;
import com.porter.platform.producer.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import java.util.UUID;

/**
 * Extracted into its own Spring bean so that the @Transactional proxy applies
 * when EventService calls writeEventAndOutbox — Spring AOP proxies only intercept
 * calls that come through the proxy, not intra-class this.method() calls.
 */
@Service
public class EventPersistenceService {

    private static final String NOTIFICATIONS_TOPIC = "notifications";

    private final EventRepository eventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public EventPersistenceService(EventRepository eventRepository,
                                   OutboxEventRepository outboxEventRepository,
                                   ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Atomically persists the event row and the outbox row in a single DB transaction.
     * If either insert fails, both roll back — guaranteeing the outbox always reflects
     * what was actually accepted.
     */
    @Transactional
    public void writeEventAndOutbox(EventRequest request) {
        UUID id = UUID.fromString(request.eventId());

        eventRepository.save(new Event(
                id,
                request.eventType(),
                request.partitionKey(),
                request.payload(),
                request.webhookUrl(),
                EventStatus.QUEUED
        ));

        EventMessage message = new EventMessage(
                request.eventId(),
                request.eventType().name(),
                request.partitionKey(),
                request.payload(),
                request.webhookUrl(),
                request.timestamp().toString(),
                0
        );

        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize EventMessage for id=" + request.eventId(), e);
        }

        outboxEventRepository.save(new OutboxEvent(id, NOTIFICATIONS_TOPIC, payload, request.partitionKey()));
    }

    @Transactional
    public void replayDeadLettered(Event event) {
        event.setStatus(EventStatus.QUEUED);
        event.setRetryCount(0);
        event.setErrorMessage(null);
        eventRepository.save(event);

        EventMessage message = new EventMessage(
                event.getId().toString(),
                event.getEventType().name(),
                event.getPartitionKey(),
                event.getPayload(),
                event.getWebhookUrl(),
                OffsetDateTime.now().toString(),
                0
        );

        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize EventMessage for replay id=" + event.getId(), e);
        }

        outboxEventRepository.save(new OutboxEvent(event.getId(), NOTIFICATIONS_TOPIC, payload, event.getPartitionKey()));
    }
}
