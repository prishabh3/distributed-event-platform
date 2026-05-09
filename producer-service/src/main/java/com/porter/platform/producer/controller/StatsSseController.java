package com.porter.platform.producer.controller;

import com.porter.platform.producer.dto.StatsResponse;
import com.porter.platform.producer.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1")
public class StatsSseController {

    private final EventService eventService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "sse-stats");
        t.setDaemon(true);
        return t;
    });

    public StatsSseController(EventService eventService) {
        this.eventService = eventService;
    }

    @Operation(summary = "Stream live stats via SSE",
               description = "Server-Sent Events stream that pushes platform stats every 2 seconds. No API key required.")
    @GetMapping(value = "/stats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamStats() {
        SseEmitter emitter = new SseEmitter(0L);

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                StatsResponse stats = eventService.getStats();
                emitter.send(SseEmitter.event().name("stats").data(stats));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, 0, 2, TimeUnit.SECONDS);

        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> future.cancel(true));
        emitter.onError(e -> future.cancel(true));
        return emitter;
    }
}
