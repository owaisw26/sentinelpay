package com.sentinelpay.payments.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.LedgerEntry;
import com.sentinelpay.payments.domain.LedgerTransaction;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.exception.InvalidTransferException;
import com.sentinelpay.payments.exception.WalletInsufficientBalanceException;
import com.sentinelpay.payments.exception.WalletNotFoundException;
import com.sentinelpay.payments.exception.WalletUnauthorizedAccess;
import com.sentinelpay.payments.repository.LedgerEntryRepository;
import com.sentinelpay.payments.repository.LedgerTransactionRepository;
import com.sentinelpay.payments.repository.WalletRepository;

import jakarta.transaction.Transactional;

@Service
public class LedgerService {
    private WalletRepository walletRepository;

    private LedgerEntryRepository ledgerEntryRepository;

    private LedgerTransactionRepository ledgerTransactionRepository;

    public LedgerService(WalletRepository walletRepository,
        LedgerEntryRepository ledgerEntryRepository, 
        LedgerTransactionRepository ledgerTransactionRepository
    ) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
    }

    // @Transactional provides the atomic behaviour, if a method throws
    // a runtime error, it will rollback
    @Transactional
    public Wallet transfer(
        UUID userId,
        UUID senderId,
        UUID receiverId,
        BigDecimal amount,
        String reference
    ) {
        // check if amount is less than zero
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Transfer amount must be positive");
        }

        if (senderId.equals(receiverId)) {
            throw new InvalidTransferException("Sender and receiver wallets must be different");
        }

        // lock wallets alphabetically
        UUID firstId;
        UUID secondId;
        Boolean flag;
        if (senderId.compareTo(receiverId) < 0) {
            firstId = senderId;
            secondId = receiverId;
            flag = true;
        } else {
            firstId = receiverId;
            secondId = senderId;
            flag = false;
        }

        // lock wallets and verify sending 
        Wallet walletOne = walletRepository.findByIdForUpdate(firstId).orElseThrow(() -> new WalletNotFoundException(firstId));

        Wallet walletTwo = walletRepository.findByIdForUpdate(secondId).orElseThrow(() -> new WalletNotFoundException(secondId));

        // make sure wallet one is the sender
        if (!flag) {
            Wallet temp = walletOne;
            walletOne = walletTwo;
            walletTwo = temp;
        }

        // check sending wallet belongs to the user
        if (!walletOne.getUser().getUserId().equals(userId)) {
            throw new WalletUnauthorizedAccess(userId);
        }

        // check wallet have the same currency
        if (!walletTwo.getCurrency().equals(walletOne.getCurrency())) {
            throw new InvalidTransferException("Wallets must have same currency");
        }

        // check balance for wallet one is sufficient
        if (walletOne.getBalance().compareTo(amount) < 0) {
            throw new WalletInsufficientBalanceException(walletOne.getId(), amount);
        }

        // create the ledger transaction
        UUID transactionId = UUID.randomUUID();
        LedgerTransaction transaction = new LedgerTransaction(transactionId, reference, "Transfer", LocalDateTime.now());

        // create the ledger entries
        UUID entryOneId = UUID.randomUUID();
        LedgerEntry entryOne = new LedgerEntry(entryOneId, transaction, walletOne, amount.negate(), LocalDateTime.now());

        UUID entryTwoId = UUID.randomUUID();
        LedgerEntry entryTwo = new LedgerEntry(entryTwoId, transaction, walletTwo,  amount, LocalDateTime.now());

        // verify the sum of the entries is 0
        if (entryOne.getAmount()
        .add(entryTwo.getAmount())
        .compareTo(BigDecimal.ZERO) != 0) {
            throw new RuntimeException("entry amounts don't add up");
        }

        BigDecimal walletOneBalance = walletOne.getBalance();
        walletOneBalance = walletOneBalance.subtract(amount);

        BigDecimal walletTwoBalance = walletTwo.getBalance();
        walletTwoBalance = walletTwoBalance.add(amount);

        walletOne.setBalance(walletOneBalance);
        walletTwo.setBalance(walletTwoBalance);

        // save all entries to the database
        ledgerTransactionRepository.save(transaction);

        ledgerEntryRepository.save(entryOne);
        ledgerEntryRepository.save(entryTwo);

        walletRepository.save(walletTwo);
        return walletRepository.save(walletOne);
    }
}
