package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.repository.PostgresFixedWindowRateLimitRepository;
import com.sentinelpay.payments.service.PayeeCheckService;
import com.sentinelpay.payments.service.RateLimitOperation;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;

import jakarta.transaction.Transactional;

@SpringBootTest(properties = {
    "sentinelpay.rate-limit.payee-checks.max-requests=1",
    "sentinelpay.rate-limit.payment-creation.max-requests=1",
    "sentinelpay.rate-limit.window=PT5M"
})
@AutoConfigureMockMvc
@Transactional
class Day11RateLimitIntegrationTest extends AbstractIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private WalletService walletService;
    @Autowired private PayeeCheckService payeeCheckService;
    @Autowired private PostgresFixedWindowRateLimitRepository repository;

    @Test
    void endpointLimitsReturnRetryAfterForBothOperations() throws Exception {
        User senderUser = userService.createCustomer("Limited Sender");
        User receiverUser = userService.createCustomer("Limited Receiver");
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        String token = issueToken(senderUser.getUserId());

        String checkBody = """
            {"receiverWalletId":"%s","suppliedName":"Limited Receiver"}
            """.formatted(receiver.getId());
        mockMvc.perform(post("/payee-checks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(checkBody))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/payee-checks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(checkBody))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
            .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));

        UUID secondUserId = UUID.randomUUID();
        User secondSenderUser = userService.createCustomer("Payment Limited " + secondUserId);
        Wallet secondSender = walletService.createWallet(
            secondSenderUser.getUserId(), "AUD"
        );
        UUID checkId = payeeCheckService.createCheck(
            secondSenderUser.getUserId(), receiver.getId(), "Limited Receiver"
        ).getId();
        String secondToken = issueToken(secondSenderUser.getUserId());
        String paymentBody = """
            {
              "senderWalletId":"%s",
              "receiverWalletId":"%s",
              "amount":10.00,
              "currency":"AUD",
              "reference":"rate limit",
              "payeeCheckId":"%s",
              "acceptNameMismatch":false
            }
            """.formatted(secondSender.getId(), receiver.getId(), checkId);
        mockMvc.perform(post("/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken)
                .header("Idempotency-Key", "first")
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentBody))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken)
                .header("Idempotency-Key", "second")
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentBody))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void atomicCounterIsSharedAcrossConcurrentCallers() throws Exception {
        UUID subject = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> increment(
                subject, ready, start
            ));
            Future<Boolean> second = executor.submit(() -> increment(
                subject, ready, start
            ));
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            List<Boolean> results = List.of(
                first.get(5, TimeUnit.SECONDS),
                second.get(5, TimeUnit.SECONDS)
            );
            assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean increment(UUID subject, CountDownLatch ready,
        CountDownLatch start) throws Exception {
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        return repository.increment(
            subject, RateLimitOperation.PAYMENT_CREATE, 1, 300
        ).allowed();
    }

    private String issueToken(UUID userId) throws Exception {
        return mockMvc.perform(post("/dev/token/{userId}", userId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }
}
