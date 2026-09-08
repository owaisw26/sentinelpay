package com.sentinelpay.payments.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sentinelpay.payments.domain.PayeeRegistryEntry;

public interface PayeeRegistryRepository
    extends JpaRepository<PayeeRegistryEntry, UUID> {
    Optional<PayeeRegistryEntry> findByReceiverWalletIdAndActiveTrue(
        UUID receiverWalletId
    );
}
