package com.sentinelpay.payments.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.LedgerEntry;
import com.sentinelpay.payments.domain.LedgerTransaction;
import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentReservation;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.exception.InvalidTransferException;
import com.sentinelpay.payments.exception.WalletInsufficientBalanceException;
import com.sentinelpay.payments.exception.WalletNotFoundException;
import com.sentinelpay.payments.exception.WalletUnauthorizedAccess;
import com.sentinelpay.payments.repository.LedgerEntryRepository;
import com.sentinelpay.payments.repository.LedgerTransactionRepository;
import com.sentinelpay.payments.repository.PaymentReservationRepository;
import com.sentinelpay.payments.repository.WalletRepository;

import jakarta.transaction.Transactional;

@Service
public class LedgerService {
    private WalletRepository walletRepository;

    private LedgerEntryRepository ledgerEntryRepository;

    private LedgerTransactionRepository ledgerTransactionRepository;

    private final PaymentReservationRepository paymentReservationRepository;

    public LedgerService(WalletRepository walletRepository,
        LedgerEntryRepository ledgerEntryRepository, 
        LedgerTransactionRepository ledgerTransactionRepository,
        PaymentReservationRepository paymentReservationRepository
    ) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.paymentReservationRepository = paymentReservationRepository;
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
        validateTransfer(senderId, receiverId, amount);

        LockedWallets wallets = lockWallets(senderId, receiverId);

        // User-facing transfers must verify ownership at this boundary.
        if (!wallets.sender().getUser().getUserId().equals(userId)) {
            throw new WalletUnauthorizedAccess(userId);
        }

        return executeTransfer(
            wallets.sender(),
            wallets.receiver(),
            amount,
            reference,
            null,
            null
        );
    }

    @Transactional
    public PaymentWallets authorizePayment(
        UUID senderId,
        UUID receiverId,
        BigDecimal amount,
        String currency
    ) {
        validateTransfer(senderId, receiverId, amount);
        LockedWallets wallets = lockWallets(senderId, receiverId);
        if (!currency.equals(wallets.sender().getCurrency()) ||
            !currency.equals(wallets.receiver().getCurrency())) {
            throw new InvalidTransferException(
                "Payment currency must match both wallet currencies"
            );
        }
        if (wallets.sender().getAvailableBalance().compareTo(amount) < 0) {
            throw new WalletInsufficientBalanceException(senderId, amount);
        }
        return new PaymentWallets(wallets.sender(), wallets.receiver());
    }

    @Transactional
    public void reservePayment(Payment payment) {
        UUID senderId = payment.getSenderWallet().getId();
        UUID receiverId = payment.getReceiverWallet().getId();

        validateTransfer(senderId, receiverId, payment.getAmount());
        LockedWallets wallets = lockWallets(senderId, receiverId);
        validatePaymentCurrencies(payment, wallets);

        PaymentReservation existing = paymentReservationRepository
            .findByPaymentIdForUpdate(payment.getId())
            .orElse(null);

        if (existing != null) {
            validateReservation(payment, wallets.sender(), existing);
            if (!existing.isActive()) {
                throw new IllegalStateException("Payment reservation is already final");
            }
            return;
        }

        if (wallets.sender().getAvailableBalance().compareTo(payment.getAmount()) < 0) {
            throw new WalletInsufficientBalanceException(senderId, payment.getAmount());
        }

        wallets.sender().reserve(payment.getAmount());
        paymentReservationRepository.save(
            new PaymentReservation(payment, wallets.sender(), LocalDateTime.now())
        );
    }

    @Transactional
    public Wallet settlePayment(Payment payment) {
        UUID senderId = payment.getSenderWallet().getId();
        UUID receiverId = payment.getReceiverWallet().getId();
        BigDecimal amount = payment.getAmount();

        validateTransfer(senderId, receiverId, amount);

        LockedWallets wallets = lockWallets(senderId, receiverId);

        validatePaymentCurrencies(payment, wallets);

        PaymentReservation reservation = paymentReservationRepository
            .findByPaymentIdForUpdate(payment.getId())
            .orElseThrow(() -> new IllegalStateException(
                "Payment has no active funds reservation"
            ));
        validateReservation(payment, wallets.sender(), reservation);

        return executeTransfer(
            wallets.sender(),
            wallets.receiver(),
            amount,
            payment.getReference(),
            payment.getId(),
            reservation
        );
    }

    @Transactional
    public void releasePayment(Payment payment) {
        UUID senderId = payment.getSenderWallet().getId();
        Wallet sender = walletRepository.findByIdForUpdate(senderId)
            .orElseThrow(() -> new WalletNotFoundException(senderId));
        PaymentReservation reservation = paymentReservationRepository
            .findByPaymentIdForUpdate(payment.getId())
            .orElseThrow(() -> new IllegalStateException(
                "Payment has no active funds reservation"
            ));

        validateReservation(payment, sender, reservation);
        sender.releaseReservation(reservation.getAmount());
        reservation.release(LocalDateTime.now());
    }

    /**
     * Releases a reservation when one exists. The optional form keeps
     * pre-authorization payments created before reservation-at-creation was
     * introduced resolvable after a rolling deployment.
     */
    @Transactional
    public boolean releasePaymentIfPresent(Payment payment) {
        UUID senderId = payment.getSenderWallet().getId();
        Wallet sender = walletRepository.findByIdForUpdate(senderId)
            .orElseThrow(() -> new WalletNotFoundException(senderId));
        PaymentReservation reservation = paymentReservationRepository
            .findByPaymentIdForUpdate(payment.getId())
            .orElse(null);

        if (reservation == null) {
            return false;
        }
        validateReservationIdentity(payment, sender, reservation);
        if (!reservation.isActive()) {
            return false;
        }
        sender.releaseReservation(reservation.getAmount());
        reservation.release(LocalDateTime.now());
        return true;
    }

    private void validateTransfer(
        UUID senderId,
        UUID receiverId,
        BigDecimal amount
    ) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Transfer amount must be positive");
        }

        if (senderId.equals(receiverId)) {
            throw new InvalidTransferException("Sender and receiver wallets must be different");
        }
    }

    private LockedWallets lockWallets(UUID senderId, UUID receiverId) {
        // lock wallets alphabetically
        UUID firstId;
        UUID secondId;
        boolean senderIsFirst;
        if (senderId.compareTo(receiverId) < 0) {
            firstId = senderId;
            secondId = receiverId;
            senderIsFirst = true;
        } else {
            firstId = receiverId;
            secondId = senderId;
            senderIsFirst = false;
        }

        Wallet firstWallet = walletRepository.findByIdForUpdate(firstId)
            .orElseThrow(() -> new WalletNotFoundException(firstId));
        Wallet secondWallet = walletRepository.findByIdForUpdate(secondId)
            .orElseThrow(() -> new WalletNotFoundException(secondId));

        return senderIsFirst
            ? new LockedWallets(firstWallet, secondWallet)
            : new LockedWallets(secondWallet, firstWallet);
    }

    private Wallet executeTransfer(
        Wallet senderWallet,
        Wallet receiverWallet,
        BigDecimal amount,
        String reference,
        UUID paymentId,
        PaymentReservation reservation
    ) {

        // check wallet have the same currency
        if (!receiverWallet.getCurrency().equals(senderWallet.getCurrency())) {
            throw new InvalidTransferException("Wallets must have same currency");
        }

        // check balance for wallet one is sufficient
        if (reservation == null && senderWallet.getAvailableBalance().compareTo(amount) < 0) {
            throw new WalletInsufficientBalanceException(senderWallet.getId(), amount);
        }

        // create the ledger transaction
        UUID transactionId = UUID.randomUUID();
        LedgerTransaction transaction = new LedgerTransaction(
            transactionId,
            reference,
            paymentId == null ? "Transfer" : "PaymentSettlement",
            LocalDateTime.now(),
            paymentId
        );

        // create the ledger entries
        UUID entryOneId = UUID.randomUUID();
        LedgerEntry entryOne = new LedgerEntry(entryOneId, transaction, senderWallet, amount.negate(), LocalDateTime.now());

        UUID entryTwoId = UUID.randomUUID();
        LedgerEntry entryTwo = new LedgerEntry(entryTwoId, transaction, receiverWallet, amount, LocalDateTime.now());

        // verify the sum of the entries is 0
        if (entryOne.getAmount()
        .add(entryTwo.getAmount())
        .compareTo(BigDecimal.ZERO) != 0) {
            throw new RuntimeException("entry amounts don't add up");
        }

        if (reservation == null) {
            senderWallet.setBalance(senderWallet.getBalance().subtract(amount));
        } else {
            senderWallet.captureReservation(amount);
            reservation.capture(LocalDateTime.now());
        }

        BigDecimal receiverBalance = receiverWallet.getBalance();
        receiverBalance = receiverBalance.add(amount);

        receiverWallet.setBalance(receiverBalance);

        // save all entries to the database
        ledgerTransactionRepository.save(transaction);

        ledgerEntryRepository.save(entryOne);
        ledgerEntryRepository.save(entryTwo);

        walletRepository.save(receiverWallet);
        return walletRepository.save(senderWallet);
    }

    private void validatePaymentCurrencies(
        Payment payment,
        LockedWallets wallets
    ) {
        String paymentCurrency = payment.getCurrency();
        if (!paymentCurrency.equals(wallets.sender().getCurrency()) ||
            !paymentCurrency.equals(wallets.receiver().getCurrency())) {
            throw new InvalidTransferException(
                "Payment currency must match both wallet currencies"
            );
        }
    }

    private void validateReservation(
        Payment payment,
        Wallet sender,
        PaymentReservation reservation
    ) {
        validateReservationIdentity(payment, sender, reservation);
        if (!reservation.isActive()) {
            throw new IllegalStateException("Payment reservation is not active");
        }
    }

    private void validateReservationIdentity(
        Payment payment,
        Wallet sender,
        PaymentReservation reservation
    ) {
        if (!reservation.getWallet().getId().equals(sender.getId()) ||
            reservation.getAmount().compareTo(payment.getAmount()) != 0 ||
            !reservation.getCurrency().equals(payment.getCurrency())) {
            throw new IllegalStateException("Payment reservation does not match payment");
        }
    }

    public record PaymentWallets(Wallet sender, Wallet receiver) {}

    private record LockedWallets(Wallet sender, Wallet receiver) {}
}
