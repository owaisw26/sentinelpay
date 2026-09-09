package com.sentinelpay.payments.service.risk;

import java.util.Set;

import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.OutboxEvent;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.repository.OutboxEventRepository;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.RiskDecisionInboxRepository;
import com.sentinelpay.payments.security.PayloadHasher;
import com.sentinelpay.payments.service.LedgerService;

import jakarta.transaction.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class RiskDecisionService {
    private static final Set<PaymentStatus> TERMINAL_STATUSES = Set.of(
        PaymentStatus.SETTLED, PaymentStatus.FAILED, PaymentStatus.BLOCKED
    );

    private final RiskDecisionInboxRepository inboxRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;

    public RiskDecisionService(
        RiskDecisionInboxRepository inboxRepository,
        PaymentRepository paymentRepository,
        OutboxEventRepository outboxEventRepository,
        LedgerService ledgerService,
        ObjectMapper objectMapper
    ) {
        this.inboxRepository = inboxRepository;
        this.paymentRepository = paymentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.ledgerService = ledgerService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void apply(RiskDecisionEnvelope envelope) {
        validate(envelope);
        String canonical = objectMapper.writeValueAsString(envelope);
        if (inboxRepository.claim(
            envelope, PayloadHasher.sha256(canonical)
        ) == RiskDecisionInboxRepository.ClaimResult.DUPLICATE) {
            return;
        }

        var payload = envelope.payload();
        var payment = paymentRepository.findByIdForUpdate(payload.paymentId())
            .orElseThrow(() -> new IllegalStateException(
                "Risk decision payment does not exist"
            ));

        if (TERMINAL_STATUSES.contains(payment.getStatus())) {
            inboxRepository.markProcessed(envelope.eventId(), "IGNORED_TERMINAL");
            return;
        }

        if (payment.getScreeningSequence() == null ||
            payment.getScreeningSequence() != payload.sourceAggregateSequence() ||
            payment.getStatus() != PaymentStatus.SCREENING) {
            inboxRepository.markProcessed(envelope.eventId(), "IGNORED_STALE");
            return;
        }

        payment.recordRiskDecision(payload.decisionId(), payload.action().name());
        switch (payload.action()) {
            case APPROVE -> approve(envelope, payment);
            case HOLD -> payment.transitionTo(PaymentStatus.HELD);
            case BLOCK -> payment.transitionTo(PaymentStatus.BLOCKED);
        }
        inboxRepository.markProcessed(
            envelope.eventId(), "APPLIED_" + payload.action().name()
        );
    }

    private void approve(
        RiskDecisionEnvelope envelope,
        com.sentinelpay.payments.domain.Payment payment
    ) {
        payment.transitionTo(PaymentStatus.APPROVED);
        ledgerService.reservePayment(payment);
        payment.transitionTo(PaymentStatus.PROCESSING);
        outboxEventRepository.save(new OutboxEvent(
            payment.getId(),
            "PAYMENT_APPROVED",
            envelope.payload().sourceAggregateSequence() + 1,
            objectMapper.valueToTree(java.util.Map.of(
                "paymentId", payment.getId()
            )),
            envelope.correlationId(),
            envelope.eventId()
        ));
    }

    private void validate(RiskDecisionEnvelope envelope) {
        if (envelope == null || envelope.payload() == null ||
            envelope.eventId() == null || envelope.aggregateId() == null ||
            envelope.correlationId() == null || envelope.causationId() == null ||
            envelope.schemaVersion() != 1 ||
            !"RISK_DECISION_MADE".equals(envelope.eventType()) ||
            envelope.aggregateSequence() < 1 ||
            envelope.payload().sourceAggregateSequence() < 1 ||
            envelope.payload().decisionId() == null ||
            envelope.payload().paymentId() == null ||
            envelope.payload().sourceEventId() == null ||
            envelope.payload().action() == null ||
            envelope.payload().featureVersion() == null ||
            envelope.payload().rulesetVersion() == null ||
            envelope.payload().modelVersion() == null ||
            envelope.payload().reasonCodes() == null ||
            envelope.payload().score() < 0 ||
            (envelope.payload().action() != RiskAction.APPROVE &&
                envelope.payload().reasonCodes().isEmpty()) ||
            !envelope.eventId().equals(envelope.payload().decisionId()) ||
            !envelope.aggregateId().equals(envelope.payload().paymentId()) ||
            envelope.aggregateSequence()
                != envelope.payload().sourceAggregateSequence() ||
            !envelope.causationId().equals(envelope.payload().sourceEventId())) {
            throw new IllegalArgumentException("Invalid risk decision envelope");
        }
    }
}
