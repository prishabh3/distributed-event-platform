package com.porter.platform.producer.controller;

import com.porter.platform.producer.domain.Event;
import com.porter.platform.producer.dto.ErrorResponse;
import com.porter.platform.producer.dto.EventRequest;
import com.porter.platform.producer.dto.EventResponse;
import com.porter.platform.producer.dto.StatsResponse;
import com.porter.platform.producer.service.EventService;
import io.micrometer.observation.annotation.Observed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Events", description = "Event ingestion, lookup, and replay")
@RestController
@RequestMapping("/api/v1")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @Operation(summary = "Publish a new event",
               description = "Accepts an event for async processing. Returns 202 for new events, 200 for duplicates (idempotent).")
    @PostMapping("/events")
    @Observed(name = "events.ingest", contextualName = "POST /api/v1/events")
    public ResponseEntity<EventResponse> ingestEvent(@Valid @RequestBody EventRequest request) {
        EventResponse response = eventService.acceptEvent(request);
        if ("DUPLICATE".equals(response.status())) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Operation(summary = "Lookup an event by ID")
    @GetMapping("/events/{id}")
    public ResponseEntity<Event> getEvent(@PathVariable UUID id) {
        Event event = eventService.getEvent(id);
        if (event == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(event);
    }

    @Operation(summary = "Replay a dead-lettered event",
               description = "Requeues a DEAD_LETTERED event through the outbox for reprocessing.")
    @PostMapping("/events/{id}/replay")
    public ResponseEntity<?> replayEvent(@PathVariable UUID id) {
        try {
            EventResponse response = eventService.replayEvent(id);
            if (response == null) return ResponseEntity.notFound().build();
            log.info("Replay requested for event: {}", id);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of(400, "Bad Request", e.getMessage(), "/api/v1/events/" + id + "/replay"));
        }
    }

    @Operation(summary = "Get platform statistics")
    @GetMapping("/stats")
    public ResponseEntity<StatsResponse> getStats() {
        return ResponseEntity.ok(eventService.getStats());
    }
}
