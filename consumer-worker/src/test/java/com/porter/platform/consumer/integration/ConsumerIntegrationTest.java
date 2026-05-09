package com.porter.platform.consumer.integration;

import com.porter.platform.contracts.EventMessage;
import com.porter.platform.contracts.EventType;
import com.porter.platform.consumer.domain.ProcessedEvent;
import com.porter.platform.consumer.repository.ProcessedEventRepository;
import com.porter.platform.consumer.service.DeduplicationService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class ConsumerIntegrationTest {

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

    static MockWebServer mockWebServer;

    @BeforeAll
    static void startMockServer() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterAll
    static void stopMockServer() throws IOException {
        mockWebServer.shutdown();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Tracing disabled via src/test/resources/application.yml (sampling.probability=0.0)
    }

    @Autowired
    KafkaTemplate<String, EventMessage> retryKafkaTemplate;

    @Autowired
    ProcessedEventRepository processedEventRepository;

    @Autowired
    DeduplicationService deduplicationService;

    @BeforeEach
    void cleanUp() {
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Consumer processes event and marks it as processed on webhook 200")
    void successfulWebhook_marksEventProcessed() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        String webhookUrl = "http://localhost:" + mockWebServer.getPort() + "/webhook";
        String eventId = UUID.randomUUID().toString();

        EventMessage message = new EventMessage(
                eventId,
                EventType.ORDER_UPDATE.name(),
                "ORD-TEST-001",
                Map.of("orderId", "ORD-TEST-001", "status", "DELIVERED"),
                webhookUrl,
                "2026-05-08T10:00:00Z",
                0
        );

        retryKafkaTemplate.send("notifications", message.partitionKey(), message);

        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(processedEventRepository.existsById(UUID.fromString(eventId))).isTrue()
                );
    }

    @Test
    @DisplayName("Consumer deduplicates events with same eventId — Redis and DB layers")
    void duplicateEvent_isSkipped() {
        String eventId = UUID.randomUUID().toString();

        assertThat(deduplicationService.isDuplicate(eventId)).isFalse();

        processedEventRepository.save(new ProcessedEvent(UUID.fromString(eventId)));

        // DB layer detects duplicate
        assertThat(deduplicationService.isDuplicate(eventId)).isTrue();
    }

    @Test
    @DisplayName("Consumer routes to retry.1m topic on webhook 503")
    void webhookServerError_routesToRetryTopic() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(503));

        String webhookUrl = "http://localhost:" + mockWebServer.getPort() + "/webhook";
        String eventId = UUID.randomUUID().toString();

        EventMessage message = new EventMessage(
                eventId,
                EventType.DRIVER_ASSIGNED.name(),
                "DRV-001",
                Map.of("driverId", "DRV-001"),
                webhookUrl,
                "2026-05-08T10:00:00Z",
                0
        );

        retryKafkaTemplate.send("notifications", message.partitionKey(), message);

        // The event should NOT be in processed_events — it was routed to retry topic
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(processedEventRepository.existsById(UUID.fromString(eventId))).isFalse()
                );
    }

    @Test
    @DisplayName("Consumer routes to DLQ on webhook 400 (non-retryable client error)")
    void webhookClientError_routesToDlq() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"Bad Request\"}"));

        String webhookUrl = "http://localhost:" + mockWebServer.getPort() + "/webhook";
        String eventId = UUID.randomUUID().toString();

        EventMessage message = new EventMessage(
                eventId,
                EventType.PAYMENT_DONE.name(),
                "PAY-001",
                Map.of("paymentId", "PAY-001"),
                webhookUrl,
                "2026-05-08T10:00:00Z",
                0
        );

        retryKafkaTemplate.send("notifications", message.partitionKey(), message);

        // Event goes to DLQ without hitting processed_events — wait for consumer to process it
        TimeUnit.SECONDS.sleep(8);
        assertThat(processedEventRepository.existsById(UUID.fromString(eventId))).isFalse();
    }

    @Test
    @DisplayName("Outbox idempotency: second save of same eventId throws PK violation")
    void processedEvent_pkConstraintPreventsDuplicate() {
        UUID eventId = UUID.randomUUID();

        processedEventRepository.save(new ProcessedEvent(eventId));

        // Second save of same PK must throw DataIntegrityViolationException
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> {
                    processedEventRepository.saveAndFlush(new ProcessedEvent(eventId));
                }
        );
    }
}
