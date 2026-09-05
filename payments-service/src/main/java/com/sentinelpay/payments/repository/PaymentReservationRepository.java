package com.sentinelpay.payments.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sentinelpay.payments.domain.PaymentReservation;

import jakarta.persistence.LockModeType;

public interface PaymentReservationRepository
    extends JpaRepository<PaymentReservation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentReservation r where r.paymentId = :paymentId")
    Optional<PaymentReservation> findByPaymentIdForUpdate(
        @Param("paymentId") UUID paymentId
    );
}
