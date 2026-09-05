package com.sentinelpay.payments.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sentinelpay.payments.domain.ProviderAttempt;

import jakarta.persistence.LockModeType;

public interface ProviderAttemptRepository
    extends JpaRepository<ProviderAttempt, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ProviderAttempt a where a.paymentId = :paymentId")
    Optional<ProviderAttempt> findByPaymentIdForUpdate(
        @Param("paymentId") UUID paymentId
    );
}
