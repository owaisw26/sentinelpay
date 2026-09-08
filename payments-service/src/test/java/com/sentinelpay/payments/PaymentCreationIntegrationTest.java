package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.service.PayeeCheckService;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;

import jakarta.transaction.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PaymentCreationIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PayeeCheckService payeeCheckService;

    @Test
    void identicalRetryReturnsExactOriginalBodyAndReplayHeader() throws Exception {
        Fixture fixture = fixture();
        String key = "opaque-retry-key-" + UUID.randomUUID();
        String body = paymentBody(fixture, "125.00", "AUD", "invoice-42");

        MvcResult first = create(fixture, key, body)
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotency-Replayed"))
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.senderWalletId")
                .value(fixture.sender().getId().toString()))
            .andExpect(jsonPath("$.receiverWalletId")
                .value(fixture.receiver().getId().toString()))
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.requestHash").doesNotExist())
            .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
            .andExpect(jsonPath("$.providerPaymentId").doesNotExist())
            .andReturn();

        MvcResult replay = create(fixture, key, body)
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andReturn();

        assertEquals(
            first.getResponse().getContentAsString(),
            replay.getResponse().getContentAsString()
        );

        String paymentId = com.jayway.jsonpath.JsonPath.read(
            first.getResponse().getContentAsString(), "$.id"
        );
        mockMvc.perform(get("/payments/{id}", paymentId)
                .header(HttpHeaders.AUTHORIZATION,
                    "Bearer " + fixture.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.senderWalletId")
                .value(fixture.sender().getId().toString()))
            .andExpect(jsonPath("$.receiverWalletId")
                .value(fixture.receiver().getId().toString()))
            .andExpect(jsonPath("$.requestHash").doesNotExist());
    }

    @Test
    void reusingKeyForDifferentRequestReturnsProblemDetail() throws Exception {
        Fixture fixture = fixture();
        String key = "conflict-key-" + UUID.randomUUID();

        create(fixture, key,
            paymentBody(fixture, "10.00", "AUD", "first"))
            .andExpect(status().isCreated());

        create(fixture, key,
            paymentBody(fixture, "11.00", "AUD", "first"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value(
                "urn:sentinelpay:problem:idempotency-key-reused"))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.errorCode")
                .value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void currencyAndPrecisionFailBeforePaymentPersistence() throws Exception {
        Fixture fixture = fixture();
        long before = paymentRepository.count();

        create(fixture, "usd-" + UUID.randomUUID(),
            paymentBody(fixture, "10.00", "USD", "wrong currency"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        create(fixture, "precision-" + UUID.randomUUID(),
            paymentBody(fixture, "10.001", "AUD", "too precise"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        assertEquals(before, paymentRepository.count());
    }

    @Test
    void paymentOwnershipIsHiddenBehindNotFound() throws Exception {
        Fixture fixture = fixture();
        String key = "owned-" + UUID.randomUUID();
        MvcResult created = create(fixture, key,
            paymentBody(fixture, "10.00", "AUD", "private"))
            .andExpect(status().isCreated())
            .andReturn();
        String paymentId = com.jayway.jsonpath.JsonPath.read(
            created.getResponse().getContentAsString(), "$.id"
        );

        User stranger = userService.createCustomer("Stranger");
        String strangerToken = issueToken(stranger.getUserId());
        mockMvc.perform(get("/payments/{id}", paymentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Payment not found"));
    }

    private Fixture fixture() throws Exception {
        User senderUser = userService.createCustomer("Payment Sender");
        User receiverUser = userService.createCustomer("Payment Receiver");
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        UUID payeeCheckId = payeeCheckService.createCheck(
            senderUser.getUserId(), receiver.getId(), "Payment Receiver"
        ).getId();
        return new Fixture(senderUser, sender, receiver, payeeCheckId,
            issueToken(senderUser.getUserId()));
    }

    private String issueToken(UUID userId) throws Exception {
        return mockMvc.perform(post("/dev/token/{userId}", userId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.ResultActions create(
        Fixture fixture,
        String key,
        String body
    ) throws Exception {
        return mockMvc.perform(post("/payments")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.token())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private String paymentBody(Fixture fixture, String amount,
        String currency, String reference) {
        return """
            {
              "senderWalletId":"%s",
              "receiverWalletId":"%s",
              "amount":%s,
              "currency":"%s",
              "reference":"%s",
              "payeeCheckId":"%s",
              "acceptNameMismatch":false
            }
            """.formatted(
                fixture.sender().getId(),
                fixture.receiver().getId(),
                amount,
                currency,
                reference,
                fixture.payeeCheckId()
            );
    }

    private record Fixture(
        User senderUser,
        Wallet sender,
        Wallet receiver,
        UUID payeeCheckId,
        String token
    ) {}
}
