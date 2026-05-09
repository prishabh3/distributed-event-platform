package com.porter.platform.consumer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.porter.platform.contracts.EventMessage;
import com.porter.platform.consumer.metrics.ConsumerMetrics;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Dispatches webhook HTTP POST calls using non-blocking WebClient.
 *
 * Retry semantics at the dispatch level:
 *   HTTP 2xx  → success
 *   HTTP 4xx  → permanent failure (NonRetryableWebhookException)
 *   HTTP 5xx, network error, timeout → transient failure (RetryableWebhookException)
 *
 * Each request includes X-Signature: sha256=<hmac> so receivers can verify authenticity.
 * A Resilience4j circuit breaker opens after sustained failures to prevent thread exhaustion.
 */
@Service
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final WebClient webClient;
    private final ConsumerMetrics metrics;
    private final ObjectMapper objectMapper;
    private final String signingSecret;
    private final long dispatchTimeoutSeconds;

    public WebhookDispatcher(
            WebClient webhookWebClient,
            ConsumerMetrics metrics,
            ObjectMapper objectMapper,
            @Value("${app.webhook.signing-secret:dev-signing-secret-change-in-prod}") String signingSecret,
            @Value("${app.webhook.dispatch-timeout-seconds:12}") long dispatchTimeoutSeconds) {
        this.webClient = webhookWebClient;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.signingSecret = signingSecret;
        this.dispatchTimeoutSeconds = dispatchTimeoutSeconds;
    }

    /**
     * Synchronously dispatches the webhook. Blocks until response or timeout.
     * Circuit breaker opens when failure rate exceeds threshold, throwing
     * RetryableWebhookException so the event enters the retry ladder.
     */
    @CircuitBreaker(name = "webhook", fallbackMethod = "dispatchFallback")
    public void dispatch(EventMessage message) {
        long startMs = System.currentTimeMillis();

        try {
            String payloadJson = objectMapper.writeValueAsString(message.payload());
            String signature = "sha256=" + hmacSha256(signingSecret, payloadJson);

            webClient.post()
                    .uri(message.webhookUrl())
                    .header("Content-Type", "application/json")
                    .header("X-Event-Id", message.eventId())
                    .header("X-Event-Type", message.eventType())
                    .header("X-Retry-Count", String.valueOf(message.retryCount()))
                    .header("X-Signature", signature)
                    .bodyValue(payloadJson)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::handle4xx)
                    .onStatus(HttpStatusCode::is5xxServerError, this::handle5xx)
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(dispatchTimeoutSeconds))
                    .block();

            long latencyMs = System.currentTimeMillis() - startMs;
            metrics.recordWebhookLatency(latencyMs);
            metrics.recordDeliverySuccess();
            log.info("Webhook delivered: eventId={} url={} latencyMs={}", message.eventId(), message.webhookUrl(), latencyMs);

        } catch (NonRetryableWebhookException | RetryableWebhookException e) {
            metrics.recordDeliveryFailure();
            throw e;
        } catch (Exception e) {
            metrics.recordDeliveryFailure();
            log.warn("Webhook dispatch error (retryable): eventId={} error={}", message.eventId(), e.getMessage());
            throw new RetryableWebhookException("Webhook dispatch failed: " + e.getMessage(), e);
        }
    }

    private void dispatchFallback(EventMessage message, Throwable t) {
        log.warn("Webhook circuit breaker open — queuing for retry: eventId={}", message.eventId());
        metrics.recordDeliveryFailure();
        throw new RetryableWebhookException("Circuit breaker open: " + t.getMessage(), t);
    }

    private Mono<? extends Throwable> handle4xx(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {
                    log.warn("Webhook permanent failure (4xx): status={} body={}", response.statusCode(), body);
                    return new NonRetryableWebhookException(
                            "Webhook returned " + response.statusCode() + ": " + body);
                });
    }

    private Mono<? extends Throwable> handle5xx(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {
                    log.warn("Webhook server error (5xx): status={}", response.statusCode());
                    return new RetryableWebhookException(
                            "Webhook returned " + response.statusCode() + ": " + body);
                });
    }

    private String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    // ── Exception types ──────────────────────────────────────────────────────

    public static class RetryableWebhookException extends RuntimeException {
        public RetryableWebhookException(String msg) { super(msg); }
        public RetryableWebhookException(String msg, Throwable cause) { super(msg, cause); }
    }

    public static class NonRetryableWebhookException extends RuntimeException {
        public NonRetryableWebhookException(String msg) { super(msg); }
    }
}
