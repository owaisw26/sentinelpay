package com.sentinelpay.payments.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sentinelpay.payments.controller.response.HeldPaymentResponse;
import com.sentinelpay.payments.domain.HeldPaymentAction;
import com.sentinelpay.payments.domain.OutboxEvent;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.exception.HeldPaymentConflictException;
import com.sentinelpay.payments.exception.HeldPaymentNotFoundException;
import com.sentinelpay.payments.repository.HeldPaymentOperationsRepository;
import com.sentinelpay.payments.repository.OutboxEventRepository;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.security.PayloadHasher;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.ObjectMapper;

@Service
public class HeldPaymentDecisionService {
    private final PaymentRepository paymentRepository;
    private final HeldPaymentOperationsRepository operations;
    private final OutboxEventRepository outboxEvents;
    private final LedgerService ledgerService;
    private final HeldPaymentQueryService queries;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public HeldPaymentDecisionService(
        PaymentRepository paymentRepository,
        HeldPaymentOperationsRepository operations,
        OutboxEventRepository outboxEvents,
        LedgerService ledgerService,
        HeldPaymentQueryService queries,
        ObjectMapper objectMapper,
        EntityManager entityManager
    ) {
        this.paymentRepository = paymentRepository;
        this.operations = operations;
        this.outboxEvents = outboxEvents;
        this.ledgerService = ledgerService;
        this.queries = queries;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    @Transactional
    public HeldPaymentResponse decide(
        UUID paymentId,
        int expectedVersion,
        HeldPaymentAction action,
        String reason,
        String idempotencyKey,
        String actorId
    ) {
        String requestHash = PayloadHasher.sha256(
            objectMapper.writeValueAsString(Map.of(
                "paymentId", paymentId,
                "expectedVersion", expectedVersion,
                "action", action.name(),
                "reason", reason
            ))
        );
        var claim = operations.claim(
            actorId, idempotencyKey, paymentId, action, requestHash
        );

        var payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(HeldPaymentNotFoundException::new);
        if (claim == HeldPaymentOperationsRepository.ClaimResult.REPLAY) {
            return queries.response(payment);
        }
        if (payment.getStatus() != PaymentStatus.HELD) {
            throw new HeldPaymentConflictException("Payment is no longer held");
        }
        if (payment.getVersion() != expectedVersion) {
            throw new HeldPaymentConflictException(
                "Held payment version changed; refresh before deciding"
            );
        }

        if (action == HeldPaymentAction.APPROVE) {
            payment.transitionTo(PaymentStatus.APPROVED);
            ledgerService.reservePayment(payment);
            payment.transitionTo(PaymentStatus.PROCESSING);
            outboxEvents.save(new OutboxEvent(
                paymentId,
                "PAYMENT_APPROVED",
                payment.getScreeningSequence() + 1,
                objectMapper.valueToTree(Map.of("paymentId", paymentId)),
                paymentId,
                payment.getRiskDecisionId()
            ));
        } else {
            ledgerService.releasePaymentIfPresent(payment);
            payment.transitionTo(PaymentStatus.BLOCKED);
        }

        operations.appendAudit(
            paymentId,
            action == HeldPaymentAction.APPROVE ? "APPROVED" : "BLOCKED",
            actorId,
            reason,
            Map.of("expectedVersion", expectedVersion)
        );
        operations.complete(actorId, idempotencyKey, payment.getStatus().name());
        entityManager.flush();
        return queries.response(payment);
    }
}
