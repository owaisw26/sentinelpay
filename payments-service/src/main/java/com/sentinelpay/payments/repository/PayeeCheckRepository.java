package com.sentinelpay.payments.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sentinelpay.payments.domain.PayeeCheck;
import com.sentinelpay.payments.domain.PayeeCheckOutcome;

import jakarta.persistence.LockModeType;

public interface PayeeCheckRepository extends JpaRepository<PayeeCheck, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PayeeCheck c where c.id = :id")
    Optional<PayeeCheck> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        select c from PayeeCheck c
        where c.requesterUserId = :requesterUserId
          and c.receiverWalletId = :receiverWalletId
          and c.registryVersion = :registryVersion
          and c.suppliedNameHash = :suppliedNameHash
          and c.outcome = :outcome
          and c.createdAt >= :notBefore
        order by c.createdAt desc
        """)
    List<PayeeCheck> findRecentSuccessfulChecks(
        @Param("requesterUserId") UUID requesterUserId,
        @Param("receiverWalletId") UUID receiverWalletId,
        @Param("registryVersion") int registryVersion,
        @Param("suppliedNameHash") String suppliedNameHash,
        @Param("outcome") PayeeCheckOutcome outcome,
        @Param("notBefore") LocalDateTime notBefore,
        Pageable pageable
    );
}
