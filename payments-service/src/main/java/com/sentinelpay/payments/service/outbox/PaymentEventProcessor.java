package com.sentinelpay.payments.service.outbox;

import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.ProcessedEventRepository;

import jakarta.transaction.Transactional;

@Service
public class PaymentEventProcessor {
    private final ProcessedEventRepository processedEventRepository;
    private final PaymentRepository paymentRepository;

    public PaymentEventProcessor(
        ProcessedEventRepository processedEventRepository,
        PaymentRepository paymentRepository
    ) {
        this.processedEventRepository = processedEventRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public void process(OutboxMessage message) {
        int claimed =
            processedEventRepository.claimEvent(message.eventId());

        if (claimed == 0) {
            return;
        }

        if ("PAYMENT_CREATED".equals(message.eventType())) {
            Payment payment = paymentRepository.findById(message.aggregateId()).orElseThrow();

            payment.transitionTo(PaymentStatus.SCREENING);
        }
    }
}
