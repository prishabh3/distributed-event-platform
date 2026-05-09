package com.porter.platform.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Canonical Kafka message contract shared between producer and consumer services.
 * Using record for immutability and efficient serialization.
 */
public record EventMessage(
        @JsonProperty("eventId")       String eventId,
        @JsonProperty("eventType")     String eventType,
        @JsonProperty("partitionKey")  String partitionKey,
        @JsonProperty("payload")       Map<String, Object> payload,
        @JsonProperty("webhookUrl")    String webhookUrl,
        @JsonProperty("timestamp")     String timestamp,
        @JsonProperty("retryCount")    int retryCount
) {

    public EventMessage withIncrementedRetry() {
        return new EventMessage(eventId, eventType, partitionKey, payload, webhookUrl, timestamp, retryCount + 1);
    }
}
