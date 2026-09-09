package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import com.sentinelpay.payments.domain.OutboxEvent;
import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.repository.OutboxEventRepository;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.PaymentReservationRepository;
import com.sentinelpay.payments.repository.WalletRepository;
import com.sentinelpay.payments.service.PaymentService;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;
import com.sentinelpay.payments.service.risk.RiskAction;
import com.sentinelpay.payments.service.risk.RiskDecisionEnvelope;
import com.sentinelpay.payments.service.risk.RiskDecisionPayload;
import com.sentinelpay.payments.service.risk.RiskDecisionService;

import jakarta.transaction.Transactional;

@SpringBootTest(properties = "sentinelpay.risk.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class RiskDecisionIntegrationTest extends AbstractIntegrationTest {
    @Autowired private UserService userService;
    @Autowired private WalletService walletService;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private PaymentReservationRepository reservationRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private RiskDecisionService riskDecisionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void approvalIsAppliedOnceAndReservesBeforeProviderWorkIsEmitted() {
        Payment payment = payment();
        OutboxEvent screening = screeningEvent(payment);
        RiskDecisionEnvelope approval = decision(
            payment, screening, 1, RiskAction.APPROVE
        );

        riskDecisionService.apply(approval);
        riskDecisionService.apply(approval);

        Payment applied = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.PROCESSING, applied.getStatus());
        assertEquals(approval.eventId(), applied.getRiskDecisionId());
        assertNotNull(reservationRepository.findById(payment.getId()).orElse(null));
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM risk_decision_inbox WHERE event_id = ?",
                Integer.class,
                approval.eventId()
            )
        );
        assertTrue(outboxRepository.findOutboxEventsByAggregateId(payment.getId())
            .stream().anyMatch(event ->
                "PAYMENT_APPROVED".equals(event.getEventType()) &&
                event.getCausationId().equals(approval.eventId())
            ));
    }

    @Test
    void unexpectedFutureSequenceCannotAdvancePayment() {
        Payment payment = payment();
        OutboxEvent screening = screeningEvent(payment);

        riskDecisionService.apply(decision(
            payment, screening, 2, RiskAction.APPROVE
        ));

        Payment unchanged = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.SCREENING, unchanged.getStatus());
        assertNull(unchanged.getRiskDecisionId());
        assertEquals(
            "IGNORED_STALE",
            jdbcTemplate.queryForObject(
                "SELECT outcome FROM risk_decision_inbox WHERE payment_id = ?",
                String.class,
                payment.getId()
            )
        );
    }

    @Test
    void decisionCannotDowngradeTerminalBlockedPayment() {
        Payment payment = payment();
        OutboxEvent screening = screeningEvent(payment);
        riskDecisionService.apply(decision(
            payment, screening, 1, RiskAction.BLOCK
        ));

        riskDecisionService.apply(decision(
            payment, screening, 1, RiskAction.APPROVE
        ));

        Payment unchanged = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.BLOCKED, unchanged.getStatus());
        assertNotNull(unchanged.getRiskDecisionId());
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM risk_decision_inbox " +
                    "WHERE payment_id = ? AND outcome = 'IGNORED_TERMINAL'",
                Integer.class,
                payment.getId()
            )
        );
    }

    private Payment payment() {
        User senderUser = userService.createCustomer("Risk Sender");
        User receiverUser = userService.createCustomer("Risk Receiver");
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        sender.setBalance(new BigDecimal("1000.00"));
        walletRepository.saveAndFlush(sender);
        Payment payment = paymentService.createPayment(
            senderUser.getUserId(), sender.getId(), receiver.getId(),
            new BigDecimal("25.00"), "AUD", "risk-screening",
            UUID.randomUUID()
        );
        assertEquals(PaymentStatus.SCREENING, payment.getStatus());
        return payment;
    }

    private OutboxEvent screeningEvent(Payment payment) {
        return outboxRepository.findOutboxEventsByAggregateId(payment.getId())
            .stream()
            .filter(event -> "PAYMENT_SCREENING_REQUESTED".equals(
                event.getEventType()
            ))
            .findFirst()
            .orElseThrow();
    }

    private RiskDecisionEnvelope decision(
        Payment payment,
        OutboxEvent screening,
        long sourceSequence,
        RiskAction action
    ) {
        UUID decisionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<String> reasons = action == RiskAction.APPROVE
            ? List.of()
            : List.of("TEST_REASON");
        return new RiskDecisionEnvelope(
            decisionId, "RISK_DECISION_MADE", 1, payment.getId(),
            sourceSequence, now, screening.getCorrelationId(),
            screening.getId(),
            new RiskDecisionPayload(
                decisionId, payment.getId(), screening.getId(),
                sourceSequence, "payment-features-v1",
                "deterministic-rules-v1", "none", 0, action, reasons, now
            )
        );
    }
}
