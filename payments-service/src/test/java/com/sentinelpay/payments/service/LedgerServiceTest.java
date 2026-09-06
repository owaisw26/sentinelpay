package com.sentinelpay.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sentinelpay.payments.domain.LedgerEntry;
import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.PaymentReservation;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.exception.WalletUnauthorizedAccess;
import com.sentinelpay.payments.exception.InvalidTransferException;
import com.sentinelpay.payments.exception.WalletInsufficientBalanceException;
import com.sentinelpay.payments.repository.LedgerEntryRepository;
import com.sentinelpay.payments.repository.LedgerTransactionRepository;
import com.sentinelpay.payments.repository.PaymentReservationRepository;
import com.sentinelpay.payments.repository.WalletRepository;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    private static final UUID SENDER_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000001"
    );
    private static final UUID RECEIVER_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000002"
    );

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Mock
    private PaymentReservationRepository paymentReservationRepository;

    private LedgerService ledgerService;
    private User senderUser;
    private Wallet sender;
    private Wallet receiver;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        senderUser = new User(UUID.randomUUID(), "Sender", "CUSTOMER", now);
        User receiverUser = new User(UUID.randomUUID(), "Receiver", "CUSTOMER", now);
        sender = new Wallet(SENDER_ID, senderUser, "AUD", new BigDecimal("100.00"), now);
        receiver = new Wallet(RECEIVER_ID, receiverUser, "AUD", BigDecimal.ZERO, now);

        when(walletRepository.findByIdForUpdate(SENDER_ID))
            .thenReturn(Optional.of(sender));
        when(walletRepository.findByIdForUpdate(RECEIVER_ID))
            .thenReturn(Optional.of(receiver));

        ledgerService = new LedgerService(
            walletRepository,
            ledgerEntryRepository,
            ledgerTransactionRepository,
            paymentReservationRepository
        );
    }

    @Test
    void settlesUsingThePersistedPaymentDetails() {
        when(walletRepository.save(any(Wallet.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        LocalDateTime now = LocalDateTime.now();
        Payment payment = new Payment(
            UUID.randomUUID(),
            1,
            sender,
            receiver,
            new BigDecimal("25.00"),
            "AUD",
            "payment-settlement",
            PaymentStatus.PROCESSING,
            UUID.randomUUID().toString(),
            "request-hash",
            now,
            now
        );
        PaymentReservation reservation = new PaymentReservation(
            payment,
            sender,
            now
        );
        sender.reserve(payment.getAmount());
        when(paymentReservationRepository.findByPaymentIdForUpdate(payment.getId()))
            .thenReturn(Optional.of(reservation));

        ledgerService.settlePayment(payment);

        assertEquals(0, new BigDecimal("75.00").compareTo(sender.getBalance()));
        assertEquals(0, new BigDecimal("25.00").compareTo(receiver.getBalance()));
        verify(ledgerEntryRepository, times(2)).save(any(LedgerEntry.class));
    }

    @Test
    void userTransferStillChecksSenderOwnership() {
        UUID anotherUserId = UUID.randomUUID();

        assertThrows(
            WalletUnauthorizedAccess.class,
            () -> ledgerService.transfer(
                anotherUserId,
                SENDER_ID,
                RECEIVER_ID,
                BigDecimal.TEN,
                "unauthorized-transfer"
            )
        );

        verifyNoInteractions(ledgerEntryRepository, ledgerTransactionRepository);
    }

    @Test
    void directTransferCannotSpendReservedFunds() {
        sender.reserve(new BigDecimal("80.00"));

        assertThrows(
            WalletInsufficientBalanceException.class,
            () -> ledgerService.transfer(
                senderUser.getUserId(),
                SENDER_ID,
                RECEIVER_ID,
                new BigDecimal("30.00"),
                "reserved-funds"
            )
        );

        verifyNoInteractions(ledgerEntryRepository, ledgerTransactionRepository);
    }

    @Test
    void settlementRejectsPaymentCurrencyThatDoesNotMatchWallets() {
        LocalDateTime now = LocalDateTime.now();
        Payment payment = new Payment(
            UUID.randomUUID(),
            1,
            sender,
            receiver,
            new BigDecimal("25.00"),
            "USD",
            "wrong-currency",
            PaymentStatus.PROCESSING,
            UUID.randomUUID().toString(),
            "request-hash",
            now,
            now
        );

        assertThrows(
            InvalidTransferException.class,
            () -> ledgerService.settlePayment(payment)
        );

        verifyNoInteractions(ledgerEntryRepository, ledgerTransactionRepository);
    }
}
