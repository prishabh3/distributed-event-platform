package com.porter.platform.producer.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String KEY_PREFIX = "idem:";

    private final RedisTemplate<String, String> redisTemplate;
    private final Duration ttl;

    public IdempotencyService(
            RedisTemplate<String, String> redisTemplate,
            @Value("${app.idempotency.ttl-seconds:86400}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * Atomically checks and sets the idempotency key.
     * Returns true if the key was absent (first time seeing this eventId).
     * Falls back to allowing the request through if Redis is unavailable,
     * relying on the DB unique constraint as a safety net.
     */
    @CircuitBreaker(name = "redis", fallbackMethod = "tryAcquireFallback")
    public boolean tryAcquire(String eventId) {
        Boolean absent = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + eventId, "1", ttl);
        return Boolean.TRUE.equals(absent);
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "isDuplicateFallback")
    public boolean isDuplicate(String eventId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + eventId));
    }

    private boolean tryAcquireFallback(String eventId, Throwable t) {
        log.warn("Redis circuit open — allowing event through (DB will catch duplicates): eventId={}", eventId);
        return true;
    }

    private boolean isDuplicateFallback(String eventId, Throwable t) {
        log.warn("Redis circuit open — skipping dedup check for eventId={}", eventId);
        return false;
    }
}
