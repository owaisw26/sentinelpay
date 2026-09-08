package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;
import com.zaxxer.hikari.HikariDataSource;

@SpringBootTest(properties = {
    "spring.datasource.hikari.maximum-pool-size=2",
    "spring.datasource.hikari.connection-timeout=1000"
})
@AutoConfigureMockMvc
class RateLimitTransactionBoundaryIntegrationTest
    extends AbstractIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private WalletService walletService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private HikariDataSource dataSource;

    private final List<User> requesters = new ArrayList<>();
    private User receiverUser;
    private Wallet receiver;

    @Test
    void concurrentRequestsDoNotHoldOuterConnectionsWhileLimiting()
        throws Exception {
        assertEquals(2, dataSource.getMaximumPoolSize());
        receiverUser = userService.createCustomer("Single Pool Receiver");
        receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        User first = userService.createCustomer("Pool Requester One");
        User second = userService.createCustomer("Pool Requester Two");
        requesters.addAll(List.of(first, second));
        String firstToken = issueToken(first.getUserId());
        String secondToken = issueToken(second.getUserId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstStatus = executor.submit(() -> createCheck(
                firstToken, ready, start
            ));
            Future<Integer> secondStatus = executor.submit(() -> createCheck(
                secondToken, ready, start
            ));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(201, firstStatus.get(5, TimeUnit.SECONDS));
            assertEquals(201, secondStatus.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private String issueToken(UUID userId) throws Exception {
        return mockMvc.perform(post("/dev/token/{userId}", userId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private int createCheck(String token, CountDownLatch ready,
        CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent requests did not start");
        }
        return mockMvc.perform(post("/payee-checks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "receiverWalletId":"%s",
                      "suppliedName":"Single Pool Receiver"
                    }
                    """.formatted(receiver.getId())))
            .andReturn().getResponse().getStatus();
    }

    @AfterEach
    void cleanUp() {
        for (User requester : requesters) {
            jdbcTemplate.update(
                "delete from payee_checks where requester_user_id = ?",
                requester.getUserId()
            );
            jdbcTemplate.update(
                "delete from api_rate_limit_windows where subject_id = ?",
                requester.getUserId()
            );
        }
        if (receiver != null) {
            jdbcTemplate.update(
                "delete from payee_registry_entries where receiver_wallet_id = ?",
                receiver.getId()
            );
            jdbcTemplate.update(
                "delete from wallets where id = ?", receiver.getId()
            );
        }
        for (User requester : requesters) {
            jdbcTemplate.update(
                "delete from users where user_id = ?", requester.getUserId()
            );
        }
        if (receiverUser != null) {
            jdbcTemplate.update(
                "delete from users where user_id = ?", receiverUser.getUserId()
            );
        }
    }
}
