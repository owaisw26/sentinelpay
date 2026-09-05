package com.sentinelpay.payments;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sentinelpay.payments.domain.OutboxEvent;
import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.exception.InvalidPaymentTransition;
import com.sentinelpay.payments.repository.OutboxEventRepository;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.service.PaymentService;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;

import jakarta.transaction.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class PaymentCreationIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String issueToken(UUID userId) throws Exception {
        return mockMvc.perform(
                post("/dev/token/{userId}", userId)
            )
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    }

    @Test
    public void identicalRetry() throws Exception {
        User userOne = userService.createUser("Tester", "CUSTOMER");
        User userTwo = userService.createUser("TesterTwo", "CUSTOMER");

        Wallet one = walletService.createWallet(userOne.getUserId(), "AUD");
        Wallet two = walletService.createWallet(userTwo.getUserId(), "AUD");

        String token = issueToken(userOne.getUserId());
        UUID idempotencyKey = UUID.randomUUID();

        // first request
        mockMvc.perform(
            post("/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-key", idempotencyKey.toString()).contentType(MediaType.APPLICATION_JSON).content("""
                {
                    "senderWallet": "%s",
                    "receiverWallet": "%s",
                    "reference": "%s",
                    "amount": "%s",
                    "currency": "%s" 
                }     
                """.formatted(one.getId(), two.getId(), "1-2-3-4", "125", "AUD")
            )
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.senderWallet.id").value(one.getId().toString()))
            .andExpect(jsonPath("$.receiverWallet.id").value(two.getId().toString()))
            .andExpect(jsonPath("$.reference").value("1-2-3-4"))
            .andExpect(jsonPath("$.amount").value(125))
            .andExpect(jsonPath("$.currency").value("AUD"))
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey.toString()))
            .andExpect(jsonPath("$.createdAt").isNotEmpty());

        Payment originalPayment = paymentRepository
            .findByIdempotencyKey(idempotencyKey)
            .orElseThrow();

        // second same request
        mockMvc.perform(
            post("/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-key", idempotencyKey.toString()).contentType(MediaType.APPLICATION_JSON).content("""
                {
                    "senderWallet": "%s",
                    "receiverWallet": "%s",
                    "reference": "%s",
                    "amount": "%s",
                    "currency": "%s" 
                }     
                """.formatted(one.getId(), two.getId(), "1-2-3-4", "125", "AUD")
            )
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(originalPayment.getId().toString()))
            .andExpect(jsonPath("$.senderWallet.id").value(one.getId().toString()))
            .andExpect(jsonPath("$.receiverWallet.id").value(two.getId().toString()))
            .andExpect(jsonPath("$.reference").value("1-2-3-4"))
            .andExpect(jsonPath("$.amount").value(125))
            .andExpect(jsonPath("$.currency").value("AUD"))
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey.toString()));
    }

    @Test
    public void differentBody() throws Exception {
        User userOne = userService.createUser("Tester", "CUSTOMER");
        User userTwo = userService.createUser("TesterTwo", "CUSTOMER");

        Wallet one = walletService.createWallet(userOne.getUserId(), "AUD");
        Wallet two = walletService.createWallet(userTwo.getUserId(), "AUD");

        String token = issueToken(userOne.getUserId());
        UUID idempotencyKey = UUID.randomUUID();

        // first request
        mockMvc.perform(
            post("/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-key", idempotencyKey.toString()).contentType(MediaType.APPLICATION_JSON).content("""
                {
                    "senderWallet": "%s",
                    "receiverWallet": "%s",
                    "reference": "%s",
                    "amount": "%s",
                    "currency": "%s" 
                }     
                """.formatted(one.getId(), two.getId(), "1-2-3-4", "125", "AUD")
            )
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.senderWallet.id").value(one.getId().toString()))
            .andExpect(jsonPath("$.receiverWallet.id").value(two.getId().toString()))
            .andExpect(jsonPath("$.amount").value(125))
            .andExpect(jsonPath("$.currency").value("AUD"))
            .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey.toString()));

        // second same request
        mockMvc.perform(
            post("/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-key", idempotencyKey.toString()).contentType(MediaType.APPLICATION_JSON).content("""
                {
                    "senderWallet": "%s",
                    "receiverWallet": "%s",
                    "reference": "%s",
                    "amount": "%s",
                    "currency": "%s" 
                }     
                """.formatted(one.getId(), two.getId(), "1-2-3-4", "126", "AUD")
            )
        )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("PAYMENT_ALREADY_EXISTS"))
            .andExpect(jsonPath("$.message").value("The payment already exists"));
    }

    @Test
    public void invalidStateTransition() throws Exception {
        User userOne = userService.createUser("Tester", "CUSTOMER");
        User userTwo = userService.createUser("TesterTwo", "CUSTOMER");

        Wallet one = walletService.createWallet(userOne.getUserId(), "AUD");
        Wallet two = walletService.createWallet(userTwo.getUserId(), "AUD");

        UUID idempotencyKey = UUID.randomUUID();

        Payment payment = paymentService.createPayment(userOne.getUserId(), one.getId(), two.getId(), new BigDecimal(10), "AUD", "1-2-3", idempotencyKey);

        assertThrows(InvalidPaymentTransition.class, () -> payment.transitionTo(PaymentStatus.SETTLED));
    }

    @Test
    public void validStateTransition() throws Exception {
        User userOne = userService.createUser("Tester", "CUSTOMER");
        User userTwo = userService.createUser("TesterTwo", "CUSTOMER");

        Wallet one = walletService.createWallet(userOne.getUserId(), "AUD");
        Wallet two = walletService.createWallet(userTwo.getUserId(), "AUD");

        UUID idempotencyKey = UUID.randomUUID();

        Payment payment = paymentService.createPayment(userOne.getUserId(), one.getId(), two.getId(), new BigDecimal(10), "AUD", "1-2-3", idempotencyKey);

        assertDoesNotThrow(() -> payment.transitionTo(PaymentStatus.SCREENING));
    }

    @Test
    public void initiatingUserCanFetchPayment() throws Exception {
        User initiatingUser = userService.createUser("Initiator", "CUSTOMER");
        User receivingUser = userService.createUser("Receiver", "CUSTOMER");

        Wallet sender = walletService.createWallet(initiatingUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receivingUser.getUserId(), "AUD");

        Payment payment = paymentService.createPayment(
            initiatingUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            new BigDecimal("10.00"),
            "AUD",
            "fetch-payment",
            UUID.randomUUID()
        );

        String token = issueToken(initiatingUser.getUserId());

        mockMvc.perform(
            get("/payments/{id}", payment.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(payment.getId().toString()))
            .andExpect(jsonPath("$.senderWallet.id").value(sender.getId().toString()))
            .andExpect(jsonPath("$.receiverWallet.id").value(receiver.getId().toString()))
            .andExpect(jsonPath("$.amount").value(10.0))
            .andExpect(jsonPath("$.currency").value("AUD"))
            .andExpect(jsonPath("$.reference").value("fetch-payment"))
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.idempotencyKey").value(payment.getIdempotencyKey().toString()))
            .andExpect(jsonPath("$.createdAt").isNotEmpty())
            .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    public void nonInitiatingUserCannotFetchPayment() throws Exception {
        User initiatingUser = userService.createUser("Initiator", "CUSTOMER");
        User receivingUser = userService.createUser("Receiver", "CUSTOMER");

        Wallet sender = walletService.createWallet(initiatingUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receivingUser.getUserId(), "AUD");

        Payment payment = paymentService.createPayment(
            initiatingUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            new BigDecimal("10.00"),
            "AUD",
            "protected-payment",
            UUID.randomUUID()
        );

        String token = issueToken(receivingUser.getUserId());

        mockMvc.perform(
            get("/payments/{id}", payment.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("PAYMENT_ACCESS_DENIED"))
            .andExpect(jsonPath("$.message").value(
                "User " + receivingUser.getUserId()
                    + " does not have access to payment " + payment.getId()
            ));
    }

    @Test
    public void validpaymentCreatedOutboxEvent() throws Exception {
        User initiatingUser = userService.createUser("Initiator", "CUSTOMER");
        User receivingUser = userService.createUser("Receiver", "CUSTOMER");

        Wallet sender = walletService.createWallet(initiatingUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receivingUser.getUserId(), "AUD");

        Payment payment = paymentService.createPayment(
            initiatingUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            new BigDecimal("10.00"),
            "AUD",
            "protected-payment",
            UUID.randomUUID()
        );

        // verify one event was created
        List<Payment> payments = paymentRepository.findAllById(List.of(payment.getId()));
        assertEquals(payments.size(), 1);

        // verify one outbox was created
        List<OutboxEvent> events = outboxEventRepository.
                                findOutboxEventsByAggregateId(payment.getId());

        assertEquals(events.size(), 1);
        OutboxEvent event = events.get(0);
        assertEquals(event.getEventType(), "PAYMENT_CREATED");
        assertEquals(event.getPublishedAt(), null);
    }

    @Test
    public void sameRequestLeavesOneOutboxEvent() throws Exception {
        User initiatingUser = userService.createUser("Initiator", "CUSTOMER");
        User receivingUser = userService.createUser("Receiver", "CUSTOMER");

        Wallet sender = walletService.createWallet(initiatingUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receivingUser.getUserId(), "AUD");

        String token = issueToken(initiatingUser.getUserId());
        UUID idempotencyKey = UUID.randomUUID();

        // first request
        MvcResult result = mockMvc.perform(
            post("/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-key", idempotencyKey.toString()).contentType(MediaType.APPLICATION_JSON).content("""
                {
                    "senderWallet": "%s",
                    "receiverWallet": "%s",
                    "reference": "%s",
                    "amount": "%s",
                    "currency": "%s"
                }
                """.formatted(sender.getId(), receiver.getId(), "1-2-3-4", "125", "AUD")
            )
        ).andReturn();

        String responsebody = result.getResponse().getContentAsString();
        JsonNode paymentJson = objectMapper.readTree(responsebody);

        UUID paymentId = UUID.fromString(
                            paymentJson.get("id").asString()
                        );

        List<Payment> payments = paymentRepository.findAllById(List.of(paymentId));
        assertEquals(payments.size(), 1);

        List<OutboxEvent> events = outboxEventRepository.
                                findOutboxEventsByAggregateId(paymentId);
        assertEquals(events.size(), 1);

        // attempt the same request
        result = mockMvc.perform(
            post("/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-key", idempotencyKey.toString()).contentType(MediaType.APPLICATION_JSON).content("""
                {
                    "senderWallet": "%s",
                    "receiverWallet": "%s",
                    "reference": "%s",
                    "amount": "%s",
                    "currency": "%s"
                }
                """.formatted(sender.getId(), receiver.getId(), "1-2-3-4", "125", "AUD")
            )
        ).andReturn();

        payments = paymentRepository.findAllById(List.of(paymentId));
        assertEquals(payments.size(), 1);

        events = outboxEventRepository.
                                findOutboxEventsByAggregateId(paymentId);
        assertEquals(events.size(), 1);
    }
}
