package com.porter.platform.producer.dto;

public record EventResponse(
        String eventId,
        String status,
        String message
) {

    public static EventResponse queued(String eventId) {
        return new EventResponse(eventId, "QUEUED", "Event accepted for processing");
    }

    public static EventResponse duplicate(String eventId) {
        return new EventResponse(eventId, "DUPLICATE", "Event already accepted — idempotent response");
    }

    public static EventResponse replayed(String eventId) {
        return new EventResponse(eventId, "QUEUED", "Event requeued for replay");
    }
}
