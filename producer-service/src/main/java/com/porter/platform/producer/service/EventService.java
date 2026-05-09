package com.porter.platform.producer.service;

import com.porter.platform.producer.domain.Event;
import com.porter.platform.producer.dto.EventRequest;
import com.porter.platform.producer.dto.EventResponse;
import com.porter.platform.producer.dto.StatsResponse;
import com.porter.platform.producer.metrics.EventMetrics;
import com.porter.platform.producer.repository.EventRepository;
import com.porter.platform.producer.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventPersistenceService persistenceService;
    private final EventRepository eventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyService idempotencyService;
    private final EventMetrics metrics;

    public EventService(EventPersistenceService persistenceService,
                        EventRepository eventRepository,
                        OutboxEventRepository outboxEventRepository,
                        IdempotencyService idempotencyService,
                        EventMetrics metrics) {
        this.persistenceService = persistenceService;
        this.eventRepository = eventRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotencyService = idempotencyService;
        this.metrics = metrics;
    }

    /**
     * Accepts an event with idempotency enforcement and transactional outbox persistence.
     *
     * Flow:
     *   1. Redis SETNX idempotency check (no DB connection held).
     *   2. Delegate to EventPersistenceService for the @Transactional DB write
     *      (cross-bean call ensures Spring's @Transactional proxy applies).
     *   3. Return 202 / 200 DUPLICATE.
     */
    public EventResponse acceptEvent(EventRequest request) {
        String eventId = request.eventId();

        if (!idempotencyService.tryAcquire(eventId)) {
            log.info("Duplicate event rejected: {}", eventId);
            metrics.recordDuplicate();
            return EventResponse.duplicate(eventId);
        }

        try {
            // Cross-bean call: @Transactional on persistenceService.writeEventAndOutbox
            // is correctly intercepted by Spring's AOP proxy
            persistenceService.writeEventAndOutbox(request);
            metrics.recordEventReceived();
            log.info("Event accepted: id={} type={} key={}", eventId, request.eventType(), request.partitionKey());
            return EventResponse.queued(eventId);
        } catch (DataIntegrityViolationException e) {
            // Safety net: DB PK violation from a concurrent duplicate that raced past Redis SETNX
            // (possible during Redis unavailability). Return DUPLICATE rather than 500.
            log.warn("Concurrent duplicate detected via DB PK constraint: {}", eventId);
            metrics.recordDuplicate();
            return EventResponse.duplicate(eventId);
        } catch (Exception e) {
            log.error("Failed to persist event {}: {}", eventId, e.getMessage(), e);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Event getEvent(UUID id) {
        return eventRepository.findById(id).orElse(null);
    }

    public EventResponse replayEvent(UUID id) {
        Event event = eventRepository.findById(id).orElse(null);
        if (event == null) return null;
        if (event.getStatus() != com.porter.platform.contracts.EventStatus.DEAD_LETTERED) {
            throw new IllegalStateException(
                "Only DEAD_LETTERED events can be replayed (current status: " + event.getStatus() + ")");
        }
        persistenceService.replayDeadLettered(event);
        log.info("Event replay initiated: id={}", id);
        return EventResponse.replayed(id.toString());
    }

    public StatsResponse getStats() {
        long pending = outboxEventRepository.countPending();
        return new StatsResponse(
                metrics.getEventsReceivedCount(),
                metrics.getEventsDeliveredCount(),
                metrics.getEventsFailedCount(),
                metrics.getDuplicatesRejectedCount(),
                pending,
                0.0
        );
    }
}
