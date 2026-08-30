package com.sentinelpay.payments.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sentinelpay.payments.domain.LedgerTransaction;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID>{
    @Query("select t from LedgerTransaction t where t.reference = :reference")
    Optional<LedgerTransaction> findTransactionByReference(@Param("reference") String reference);
}
