package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.repository.WalletRepository;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;

@SpringBootTest
@AutoConfigureMockMvc
class LedgerConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> transferReferences = new CopyOnWriteArrayList<>();
    private UUID senderUserId;
    private UUID receiverUserId;
    private UUID sourceWalletId;
    private UUID destinationWalletId;

    @Test
    void concurrentTransfersCannotOverdrawWallet() throws Exception {
        User sender = userService.createUser("Concurrency Sender", "CUSTOMER");
        User receiver = userService.createUser("Concurrency Receiver", "CUSTOMER");
        senderUserId = sender.getUserId();
        receiverUserId = receiver.getUserId();

        Wallet source = walletService.createWallet(senderUserId, "AUD");
        Wallet destination = walletService.createWallet(receiverUserId, "AUD");
        sourceWalletId = source.getId();
        destinationWalletId = destination.getId();

        source.setBalance(new BigDecimal("200.00"));
        walletRepository.saveAndFlush(source);

        String token = issueToken(senderUserId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Integer> transferRequest = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent requests did not start in time");
            }

            String reference = "concurrency-" + UUID.randomUUID();
            transferReferences.add(reference);

            return mockMvc.perform(post("/wallets/transfer")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "senderWallet": "%s",
                            "receiverWallet": "%s",
                            "reference": "%s",
                            "amount": 150.00
                        }
                        """.formatted(sourceWalletId, destinationWalletId, reference)))
                .andReturn()
                .getResponse()
                .getStatus();
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(transferRequest);
            Future<Integer> second = executor.submit(transferRequest);

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<Integer> statuses = List.of(
                first.get(10, TimeUnit.SECONDS),
                second.get(10, TimeUnit.SECONDS)
            );

            assertEquals(
                1,
                statuses.stream()
                    .filter(value -> value == HttpStatus.OK.value())
                    .count()
            );
            assertEquals(
                1,
                statuses.stream()
                    .filter(value -> value == HttpStatus.UNPROCESSABLE_CONTENT.value())
                    .count()
            );
        } finally {
            executor.shutdownNow();
        }

        Wallet updatedSource = walletRepository.findById(sourceWalletId).orElseThrow();
        Wallet updatedDestination = walletRepository.findById(destinationWalletId).orElseThrow();

        assertEquals(
            0,
            new BigDecimal("50.00").compareTo(updatedSource.getBalance())
        );
        assertEquals(
            0,
            new BigDecimal("150.00").compareTo(updatedDestination.getBalance())
        );
    }

    private String issueToken(UUID userId) throws Exception {
        return mockMvc.perform(post("/dev/token/{userId}", userId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    }

    @AfterEach
    void cleanUpTestData() {
        for (String reference : transferReferences) {
            jdbcTemplate.update(
                """
                delete from ledger_entries
                where ledger_transaction_id in (
                    select id from ledger_transactions where reference = ?
                )
                """,
                reference
            );
            jdbcTemplate.update(
                "delete from ledger_transactions where reference = ?",
                reference
            );
        }

        deleteById("wallets", "id", sourceWalletId);
        deleteById("wallets", "id", destinationWalletId);
        deleteById("users", "user_id", senderUserId);
        deleteById("users", "user_id", receiverUserId);
    }

    private void deleteById(String table, String column, UUID id) {
        if (id != null) {
            jdbcTemplate.update(
                "delete from " + table + " where " + column + " = ?",
                id
            );
        }
    }
}
