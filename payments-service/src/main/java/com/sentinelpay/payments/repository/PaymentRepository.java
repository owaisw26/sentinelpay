package com.sentinelpay.payments.repository;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sentinelpay.payments.domain.Payment;

import jakarta.persistence.LockModeType;

public interface PaymentRepository extends JpaRepository<Payment, UUID>{
    // need to fetch payment with idempotency key probably
    @Query("select p from Payment p where p.idempotencyKey = :idempotencyKey")
    Optional<Payment> findByIdempotencyKey(@Param("idempotencyKey") UUID key);

    @Query(("select p from Payment p where p.providerPaymentId = :providerPaymentId"))
    Optional<Payment> findByProviderPaymentId(@Param("providerPaymentId") String providerPaymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") UUID paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.providerPaymentId = :providerPaymentId")
    Optional<Payment> findByProviderPaymentIdForUpdate(
        @Param("providerPaymentId") String providerPaymentId
    );
}
