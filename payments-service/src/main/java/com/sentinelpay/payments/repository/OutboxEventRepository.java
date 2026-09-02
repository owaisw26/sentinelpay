package com.sentinelpay.payments.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sentinelpay.payments.domain.OutboxEvent;

public interface OutboxEventRepository extends
                                       JpaRepository<OutboxEvent, UUID> {
    @Query("select e from OutboxEvent e where e.aggregateId = :aggregateId")
    List<OutboxEvent> findOutboxEventsByAggregateId(@Param("aggregateId")
                                                        UUID aggregateId);

                                                    @Query("select e from OutboxEvent e where e.publishedAt is null")
    List<OutboxEvent> findByPublishedAtIsNull();

}
