package com.sentinelpay.payments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sentinelpay.payments.domain.ReconciliationRun;

public interface ReconciliationRunRepository
    extends JpaRepository<ReconciliationRun, UUID> {
}
