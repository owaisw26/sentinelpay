package com.sentinelpay.payments.service.outbox;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.provider.PaymentProvider;
import com.sentinelpay.payments.provider.PaymentProviderResponse;
import com.sentinelpay.payments.provider.PaymentProviderStatus;

@ExtendWith(MockitoExtension.class)
class PaymentEventProcessorTest {

    @Mock
    private PaymentProcessingService paymentProcessingService;

    @Mock
    private PaymentProvider paymentProvider;

    @InjectMocks
    private PaymentEventProcessor paymentEventProcessor;

    @Test
    void completedEventDoesNotCallProviderAgain() {
        UUID eventId = UUID.randomUUID();
        OutboxMessage message = message(eventId, UUID.randomUUID());

        when(paymentProcessingService.isProcessed(eventId)).thenReturn(true);

        paymentEventProcessor.process(message);

        verify(paymentProcessingService, never()).claimProcessing(
            message.eventId(),
            message.aggregateId()
        );
        verifyNoInteractions(paymentProvider);
    }

    @Test
    void newEventCallsProviderAndCompletesProcessing() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        OutboxMessage message = message(eventId, paymentId);
        Payment payment = org.mockito.Mockito.mock(Payment.class);
        PaymentProviderResponse response = new PaymentProviderResponse(
            "provider-payment-id",
            PaymentProviderStatus.ACCEPTED
        );

        when(paymentProcessingService.isProcessed(eventId)).thenReturn(false);
        when(paymentProcessingService.claimProcessing(eventId, paymentId))
            .thenReturn(Optional.of(new PaymentProcessingClaim(
                payment,
                paymentId,
                leaseToken
            )));
        when(payment.getId()).thenReturn(paymentId);
        when(paymentProvider.processPayment(payment, paymentId)).thenReturn(response);

        paymentEventProcessor.process(message);

        verify(paymentProvider).processPayment(payment, paymentId);
        verify(paymentProcessingService).completeProcessing(
            eventId,
            paymentId,
            leaseToken,
            response
        );
        verify(paymentProvider).afterProcessingCompleted(payment, response);
    }

    private OutboxMessage message(UUID eventId, UUID paymentId) {
        return new OutboxMessage(
            eventId,
            paymentId,
            "PAYMENT_CREATED",
            UUID.randomUUID(),
            null,
            null
        );
    }
}
