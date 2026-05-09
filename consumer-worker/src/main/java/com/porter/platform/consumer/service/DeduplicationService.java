package com.porter.platform.consumer.service;

import com.porter.platform.consumer.domain.ProcessedEvent;
import com.porter.platform.consumer.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * Two-layer consumer deduplication:
 *   Layer 1: Redis — fast in-memory check (cache of processed event IDs).
 *   Layer 2: PostgreSQL processed_events — authoritative, survives Redis flush.
 *
 * On cache miss, falls through to DB. A DB insert failure (PK violation) means
 * another consumer instance already committed this event — skip it.
 */
@Service
public class DeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationService.class);
    private static final String KEY_PREFIX = "processed:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ProcessedEventRepository processedEventRepository;
    private final Duration redisTtl;

    public DeduplicationService(RedisTemplate<String, String> redisTemplate,
                                ProcessedEventRepository processedEventRepository,
                                @Value("${app.deduplication.ttl-hours:168}") long ttlHours) {
        this.redisTemplate = redisTemplate;
        this.processedEventRepository = processedEventRepository;
        this.redisTtl = Duration.ofHours(ttlHours);
    }

    /**
     * Returns true if the event has already been processed (duplicate).
     */
    public boolean isDuplicate(String eventId) {
        // Layer 1: Redis cache check (O(1), sub-millisecond)
        if (Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + eventId))) {
            log.debug("Duplicate detected in Redis cache: {}", eventId);
            return true;
        }
        // Layer 2: DB check (only on Redis cache miss — warm start)
        return processedEventRepository.existsById(UUID.fromString(eventId));
    }

    /**
     * Records the event as processed in both Redis and PostgreSQL.
     * Must be called within an active transaction (the caller's transaction).
     * Returns false if a concurrent consumer already processed this event.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean markProcessed(String eventId) {
        try {
            processedEventRepository.save(new ProcessedEvent(UUID.fromString(eventId)));
            // Populate Redis cache after successful DB insert
            redisTemplate.opsForValue().set(KEY_PREFIX + eventId, "1", redisTtl);
            return true;
        } catch (DataIntegrityViolationException e) {
            // PK violation — another consumer committed this event concurrently
            log.warn("Concurrent duplicate detected on DB insert for eventId={}", eventId);
            return false;
        }
    }
}
