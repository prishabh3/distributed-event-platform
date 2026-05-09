package com.porter.platform.producer.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.porter.platform.contracts.EventType;
import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.Map;

public record EventRequest(

        @NotBlank(message = "eventId is required")
        @Pattern(
                regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "eventId must be a valid UUID v4"
        )
        String eventId,

        @NotNull(message = "eventType is required")
        EventType eventType,

        @NotBlank(message = "partitionKey is required")
        @Size(max = 255, message = "partitionKey must not exceed 255 characters")
        String partitionKey,

        @NotNull(message = "payload is required")
        @NotEmpty(message = "payload must not be empty")
        Map<String, Object> payload,

        @NotBlank(message = "webhookUrl is required")
        @org.hibernate.validator.constraints.URL(message = "webhookUrl must be a valid URL")
        String webhookUrl,

        @NotNull(message = "timestamp is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        OffsetDateTime timestamp
) {}
