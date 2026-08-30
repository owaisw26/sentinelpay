package com.sentinelpay.payments.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sentinelpay.payments.domain.LedgerEntry;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID>{
    List<LedgerEntry> findAllByLedgerTransactionId(UUID transactionId);
}
