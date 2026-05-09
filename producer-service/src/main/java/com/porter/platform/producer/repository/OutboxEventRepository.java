package com.porter.platform.producer.repository;

import com.porter.platform.producer.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of unpublished rows.
     * FOR UPDATE SKIP LOCKED prevents concurrent publisher instances from processing the same rows.
     * Called inside a short REQUIRES_NEW transaction in OutboxPublisher.fetchAndClaimBatch().
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published = false
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findUnpublishedBatchForUpdate(@Param("limit") int limit);

    @Modifying
    @Query("UPDATE OutboxEvent o SET o.published = true, o.publishedAt = :now WHERE o.id = :id")
    void markAsPublished(@Param("id") Long id, @Param("now") OffsetDateTime now);

    @Query("SELECT COUNT(o) FROM OutboxEvent o WHERE o.published = false")
    long countPending();
}
