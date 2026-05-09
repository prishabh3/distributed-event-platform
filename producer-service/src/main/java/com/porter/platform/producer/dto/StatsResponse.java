package com.porter.platform.producer.dto;

public record StatsResponse(
        long totalEventsReceived,
        long totalEventsDelivered,
        long totalEventsFailed,
        long totalDuplicatesRejected,
        long pendingOutboxCount,
        double eventsPerSecond
) {}
