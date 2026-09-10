package com.sentinelpay.payments.repository;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sentinelpay.payments.domain.Payment;

import jakarta.persistence.LockModeType;

public interface PaymentRepository extends JpaRepository<Payment, UUID>{
    @Query("""
        select p from Payment p
        where p.senderWallet.user.userId = :userId
        order by p.createdAt desc, p.id desc
        """)
    List<Payment> findCustomerPayments(
        @Param("userId") UUID userId,
        Pageable pageable
    );

    @Query("""
        select p from Payment p
        where p.senderWallet.user.userId = :userId
          and (p.createdAt < :cursorCreatedAt
            or (p.createdAt = :cursorCreatedAt and p.id < :cursorId))
        order by p.createdAt desc, p.id desc
        """)
    List<Payment> findCustomerPaymentsAfter(
        @Param("userId") UUID userId,
        @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
        @Param("cursorId") UUID cursorId,
        Pageable pageable
    );

    @Query("""
        select p from Payment p
        where p.status = com.sentinelpay.payments.domain.PaymentStatus.HELD
        order by p.updatedAt desc, p.id desc
        """)
    List<Payment> findHeldPayments(Pageable pageable);

    @Query("""
        select p from Payment p
        where p.status = com.sentinelpay.payments.domain.PaymentStatus.HELD
          and (p.updatedAt < :cursorUpdatedAt
            or (p.updatedAt = :cursorUpdatedAt and p.id < :cursorId))
        order by p.updatedAt desc, p.id desc
        """)
    List<Payment> findHeldPaymentsAfter(
        @Param("cursorUpdatedAt") LocalDateTime cursorUpdatedAt,
        @Param("cursorId") UUID cursorId,
        Pageable pageable
    );
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

    @Query("""
        select p from Payment p
        where p.status = com.sentinelpay.payments.domain.PaymentStatus.PROCESSING
          and p.updatedAt < :cutoff
        order by p.updatedAt, p.id
        """)
    List<Payment> findFirstReconciliationCandidates(
        @Param("cutoff") LocalDateTime cutoff,
        Pageable pageable
    );

    @Query("""
        select p from Payment p
        where p.status = com.sentinelpay.payments.domain.PaymentStatus.PROCESSING
          and p.updatedAt < :cutoff
          and (p.updatedAt > :cursorUpdatedAt
            or (p.updatedAt = :cursorUpdatedAt and p.id > :cursorId))
        order by p.updatedAt, p.id
        """)
    List<Payment> findReconciliationCandidatesAfter(
        @Param("cutoff") LocalDateTime cutoff,
        @Param("cursorUpdatedAt") LocalDateTime cursorUpdatedAt,
        @Param("cursorId") UUID cursorId,
        Pageable pageable
    );
}
