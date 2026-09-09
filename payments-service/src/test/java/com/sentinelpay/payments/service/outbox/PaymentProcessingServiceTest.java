package com.sentinelpay.payments.service.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.ProviderAttempt;
import com.sentinelpay.payments.exception.ProviderAttemptInProgressException;
import com.sentinelpay.payments.provider.PaymentProviderResponse;
import com.sentinelpay.payments.provider.PaymentProviderStatus;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.ProcessedEventRepository;
import com.sentinelpay.payments.repository.ProviderAttemptRepository;
import com.sentinelpay.payments.service.LedgerService;

@ExtendWith(MockitoExtension.class)
class PaymentProcessingServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ProviderAttemptRepository providerAttemptRepository;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private ProviderAttempt providerAttempt;

    @Mock
    private Payment payment;

    private PaymentProcessingService paymentProcessingService;

    @BeforeEach
    void setUp() {
        paymentProcessingService = new PaymentProcessingService(
            paymentRepository,
            processedEventRepository,
            providerAttemptRepository,
            ledgerService,
            30
        );
    }

    @Test
    void acceptedResponseKeepsPaymentProcessing() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        prepareCompletion(paymentId, leaseToken);

        paymentProcessingService.completeProcessing(
            eventId,
            paymentId,
            leaseToken,
            new PaymentProviderResponse("provider-id", PaymentProviderStatus.ACCEPTED)
        );

        verify(payment).assignProviderPaymentId("provider-id");
        verify(payment, never()).transitionTo(any(PaymentStatus.class));
    }

    @Test
    void declinedResponseFailsPayment() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        prepareCompletion(paymentId, leaseToken);

        paymentProcessingService.completeProcessing(
            eventId,
            paymentId,
            leaseToken,
            new PaymentProviderResponse("provider-id", PaymentProviderStatus.DECLINED)
        );

        verify(payment).assignProviderPaymentId("provider-id");
        verify(ledgerService).releasePayment(payment);
        verify(payment).transitionTo(PaymentStatus.FAILED);
    }

    @Test
    void activeLeaseBlocksAConcurrentProviderAttempt() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findByIdForUpdate(paymentId))
            .thenReturn(Optional.of(payment));
        when(payment.getStatus()).thenReturn(PaymentStatus.PROCESSING);
        when(providerAttemptRepository.findByPaymentIdForUpdate(paymentId))
            .thenReturn(Optional.of(providerAttempt));
        when(providerAttempt.hasActiveLease(any())).thenReturn(true);

        assertThrows(
            ProviderAttemptInProgressException.class,
            () -> paymentProcessingService.claimProcessing(eventId, paymentId)
        );
    }

    @Test
    void screeningPaymentCannotBeAdvancedByProviderMessage() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findByIdForUpdate(paymentId))
            .thenReturn(Optional.of(payment));
        when(payment.getStatus()).thenReturn(PaymentStatus.SCREENING);
        when(payment.getScreeningSequence()).thenReturn(1L);

        assertThrows(
            IllegalStateException.class,
            () -> paymentProcessingService.claimProcessing(eventId, paymentId)
        );

        verify(ledgerService, never()).reservePayment(payment);
        verifyNoInteractions(providerAttemptRepository);
    }

    private void prepareCompletion(UUID paymentId, UUID leaseToken) {
        when(paymentRepository.findByIdForUpdate(paymentId))
            .thenReturn(Optional.of(payment));
        when(providerAttemptRepository.findByPaymentIdForUpdate(paymentId))
            .thenReturn(Optional.of(providerAttempt));
        when(providerAttempt.ownsLease(leaseToken)).thenReturn(true);
    }
}
