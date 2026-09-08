package com.sentinelpay.payments.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sentinelpay.payments.domain.PayeeCheck;

import jakarta.persistence.LockModeType;

public interface PayeeCheckRepository extends JpaRepository<PayeeCheck, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PayeeCheck c where c.id = :id")
    Optional<PayeeCheck> findByIdForUpdate(@Param("id") UUID id);
}
