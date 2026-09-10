package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.sentinelpay.payments.domain.OutboxEvent;
import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentReservationStatus;
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
@AutoConfigureMockMvc
@Transactional
class OperationsDashboardIntegrationTest extends AbstractIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private WalletService walletService;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentReservationRepository reservationRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private RiskDecisionService riskDecisionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void customerQueriesAreOwnershipSafeAndCursorReady() throws Exception {
        Fixture fixture = heldPayment();
        User another = userService.createCustomer("Another customer");
        String anotherToken = issueToken(another);

        mockMvc.perform(get("/payments")
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.customer())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id")
                .value(fixture.payment().getId().toString()));

        mockMvc.perform(get("/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());

        mockMvc.perform(get("/wallets")
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.customer())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].walletId")
                .value(fixture.sender().getId().toString()));
    }

    @Test
    void heldPaymentRequiresAnalystAndReturnsSafeExplanation() throws Exception {
        Fixture fixture = heldPayment();

        mockMvc.perform(get("/analyst/held-payments")
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.customer())))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/analyst/held-payments")
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.analyst())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].paymentId")
                .value(fixture.payment().getId().toString()))
            .andExpect(jsonPath("$.items[0].risk.score").value(55))
            .andExpect(jsonPath("$.items[0].risk.reasonCodes[0]")
                .value("VELOCITY_SPIKE"))
            .andExpect(jsonPath("$.items[0].providerPaymentId").doesNotExist());
    }

    @Test
    void analystApprovalIsIdempotentKeepsReservedFundsAndIsAudited()
        throws Exception {
        Fixture fixture = heldPayment();
        paymentRepository.flush();
        int expectedVersion = paymentRepository.findById(fixture.payment().getId())
            .orElseThrow().getVersion();
        String key = "held-review-" + UUID.randomUUID();
        String request = """
            {
              "expectedVersion": %d,
              "action": "APPROVE",
              "reason": "Reviewed risk explanation and confirmed payment"
            }
            """.formatted(expectedVersion);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/analyst/held-payments/{id}/decision",
                    fixture.payment().getId())
                    .header(HttpHeaders.AUTHORIZATION, bearer(fixture.analyst()))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        }

        assertEquals(
            PaymentStatus.PROCESSING,
            paymentRepository.findById(fixture.payment().getId()).orElseThrow()
                .getStatus()
        );
        assertEquals(
            new BigDecimal("125.00"),
            walletRepository.findById(fixture.sender().getId()).orElseThrow()
                .getReservedBalance()
        );
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM held_payment_actions WHERE payment_id = ?",
            Integer.class, fixture.payment().getId()
        ));
        assertTrue(outboxRepository.findOutboxEventsByAggregateId(
            fixture.payment().getId()
        ).stream().anyMatch(event -> "PAYMENT_APPROVED".equals(
            event.getEventType()
        )));

        mockMvc.perform(get("/analyst/held-payments/{id}/audit",
                fixture.payment().getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.analyst())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].action").value("APPROVED"))
            .andExpect(jsonPath("$[0].reason").value(
                "Reviewed risk explanation and confirmed payment"
            ));
    }

    @Test
    void analystBlockReleasesReservedFunds() throws Exception {
        Fixture fixture = heldPayment();
        paymentRepository.flush();
        int expectedVersion = paymentRepository.findById(fixture.payment().getId())
            .orElseThrow().getVersion();
        String request = """
            {
              "expectedVersion": %d,
              "action": "BLOCK",
              "reason": "Risk signals could not be cleared"
            }
            """.formatted(expectedVersion);

        mockMvc.perform(post("/analyst/held-payments/{id}/decision",
                fixture.payment().getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.analyst()))
                .header("Idempotency-Key", "held-block-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("BLOCKED"));

        assertEquals(PaymentReservationStatus.RELEASED,
            reservationRepository.findById(fixture.payment().getId())
                .orElseThrow().getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(
            walletRepository.findById(fixture.sender().getId()).orElseThrow()
                .getReservedBalance()
        ));
    }

    private Fixture heldPayment() throws Exception {
        User customer = userService.createCustomer("Dashboard customer");
        User payee = userService.createCustomer("Dashboard payee");
        User analyst = userService.createUser("Dashboard analyst", "ANALYST");
        Wallet sender = walletService.createWallet(customer.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(payee.getUserId(), "AUD");
        sender.setBalance(new BigDecimal("1000.00"));
        walletRepository.saveAndFlush(sender);
        Payment payment = paymentService.createPayment(
            customer.getUserId(), sender.getId(), receiver.getId(),
            new BigDecimal("125.00"), "AUD", "dashboard review",
            UUID.randomUUID()
        );
        OutboxEvent screening = outboxRepository
            .findOutboxEventsByAggregateId(payment.getId()).stream()
            .filter(event -> "PAYMENT_SCREENING_REQUESTED".equals(
                event.getEventType()
            ))
            .findFirst()
            .orElseThrow();
        UUID decisionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        riskDecisionService.apply(new RiskDecisionEnvelope(
            decisionId, "RISK_DECISION_MADE", 1, payment.getId(), 1, now,
            screening.getCorrelationId(), screening.getId(),
            new RiskDecisionPayload(
                decisionId, payment.getId(), screening.getId(), 1,
                "payment-features-v1", "deterministic-rules-v1",
                "isolation-forest-v1", 55, RiskAction.HOLD,
                List.of("VELOCITY_SPIKE"), List.of(), now
            )
        ));
        return new Fixture(customer, analyst, sender, payment);
    }

    private String bearer(User user) throws Exception {
        return "Bearer " + issueToken(user);
    }

    private String issueToken(User user) throws Exception {
        return mockMvc.perform(post("/dev/token/{userId}", user.getUserId()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private record Fixture(
        User customer,
        User analyst,
        Wallet sender,
        Payment payment
    ) {}
}
