package com.porter.platform.producer.repository;

import com.porter.platform.contracts.EventStatus;
import com.porter.platform.producer.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

    @Modifying
    @Query("UPDATE Event e SET e.status = :status, e.deliveredAt = :now WHERE e.id = :id")
    int updateStatusDelivered(@Param("id") UUID id,
                              @Param("status") EventStatus status,
                              @Param("now") OffsetDateTime now);

    @Modifying
    @Query("UPDATE Event e SET e.status = :status, e.errorMessage = :error, e.retryCount = e.retryCount + 1 WHERE e.id = :id")
    int updateStatusFailed(@Param("id") UUID id,
                           @Param("status") EventStatus status,
                           @Param("error") String error);

    @Query("SELECT COUNT(e) FROM Event e WHERE e.status = :status")
    long countByStatus(@Param("status") EventStatus status);
}
