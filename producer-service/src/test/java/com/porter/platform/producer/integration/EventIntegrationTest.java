package com.porter.platform.producer.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.porter.platform.contracts.EventType;
import com.porter.platform.producer.dto.EventRequest;
import com.porter.platform.producer.repository.EventRepository;
import com.porter.platform.producer.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class EventIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("eventplatform")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Tracing is disabled via src/test/resources/application.yml (sampling.probability=0.0)
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EventRepository eventRepository;
    @Autowired OutboxEventRepository outboxEventRepository;

    private static final String VALID_API_KEY = "prod-key-alpha-001";

    @BeforeEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/v1/events — valid request returns 202 and persists outbox row")
    void validEvent_returns202_andPersistsOutbox() throws Exception {
        String eventId = UUID.randomUUID().toString();
        EventRequest request = new EventRequest(
                eventId,
                EventType.ORDER_UPDATE,
                "ORD-001",
                Map.of("orderId", "ORD-001", "status", "PICKED_UP"),
                "https://webhook.site/test",
                OffsetDateTime.now()
        );

        mockMvc.perform(post("/api/v1/events")
                        .header("X-API-KEY", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.eventId").value(eventId));

        assertThat(eventRepository.findById(java.util.UUID.fromString(eventId))).isPresent();
        assertThat(outboxEventRepository.countPending()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/v1/events — duplicate eventId returns 200 with DUPLICATE status")
    void duplicateEvent_returns200_withDuplicateStatus() throws Exception {
        String eventId = UUID.randomUUID().toString();
        EventRequest request = new EventRequest(
                eventId,
                EventType.PAYMENT_DONE,
                "PAY-001",
                Map.of("paymentId", "PAY-001"),
                "https://webhook.site/test",
                OffsetDateTime.now()
        );
        String body = objectMapper.writeValueAsString(request);

        // First request
        mockMvc.perform(post("/api/v1/events")
                        .header("X-API-KEY", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        // Duplicate request — same eventId
        mockMvc.perform(post("/api/v1/events")
                        .header("X-API-KEY", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        // Only one event should be in DB
        assertThat(eventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/v1/events — missing API key returns 401")
    void missingApiKey_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/events — invalid API key returns 403")
    void invalidApiKey_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .header("X-API-KEY", "invalid-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/events — missing required fields returns 400")
    void missingFields_returns400() throws Exception {
        String body = """
                {
                  "eventId": "not-a-uuid",
                  "partitionKey": "k1"
                }
                """;

        mockMvc.perform(post("/api/v1/events")
                        .header("X-API-KEY", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"));
    }

    @Test
    @DisplayName("GET /api/v1/events/{id} — existing event returns 200")
    void getEvent_existingId_returns200() throws Exception {
        String eventId = UUID.randomUUID().toString();
        EventRequest request = new EventRequest(
                eventId,
                EventType.TRIP_STARTED,
                "TRIP-001",
                Map.of("tripId", "TRIP-001"),
                "https://webhook.site/test",
                OffsetDateTime.now()
        );

        mockMvc.perform(post("/api/v1/events")
                        .header("X-API-KEY", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/events/" + eventId)
                        .header("X-API-KEY", VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId));
    }

    @Test
    @DisplayName("GET /api/v1/events/{id} — non-existing event returns 404")
    void getEvent_nonExistingId_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/events/" + UUID.randomUUID())
                        .header("X-API-KEY", VALID_API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/stats — returns metrics counts")
    void stats_returnsMetrics() throws Exception {
        mockMvc.perform(get("/api/v1/stats")
                        .header("X-API-KEY", VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEventsReceived").isNumber());
    }
}
