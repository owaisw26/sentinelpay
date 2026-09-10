package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
import org.springframework.jdbc.core.JdbcTemplate;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.repository.OutboxEventRepository;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.service.PaymentCreationResult;
import com.sentinelpay.payments.service.PaymentService;
import com.sentinelpay.payments.service.PayeeCheckService;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;

@SpringBootTest
class AtomicIdempotencyConcurrencyIntegrationTest
    extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PayeeCheckService payeeCheckService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User senderUser;
    private User receiverUser;
    private Wallet sender;
    private Wallet receiver;
    private UUID paymentId;
    private UUID payeeCheckId;

    @Test
    void concurrentFirstRequestsCreateOnePaymentAndOneOutboxEvent()
        throws Exception {
        senderUser = userService.createCustomer("Idempotent Sender");
        receiverUser = userService.createCustomer("Idempotent Receiver");
        sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        jdbcTemplate.update(
            "update wallets set balance = 100.00 where id = ?", sender.getId()
        );
        payeeCheckId = payeeCheckService.createCheck(
            senderUser.getUserId(), receiver.getId(), "Idempotent Receiver"
        ).getId();
        String key = "concurrent-key-" + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<PaymentCreationResult> results;
        try {
            Future<PaymentCreationResult> first = executor.submit(() ->
                createConcurrently(key, ready, start)
            );
            Future<PaymentCreationResult> second = executor.submit(() ->
                createConcurrently(key, ready, start)
            );
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            results = List.of(
                first.get(10, TimeUnit.SECONDS),
                second.get(10, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
        }

        assertEquals(results.get(0).response(), results.get(1).response());
        assertEquals(1, results.stream()
            .filter(PaymentCreationResult::replayed).count());
        paymentId = results.getFirst().payment().getId();
        List<Payment> matching = paymentRepository.findAll().stream()
            .filter(payment -> key.equals(payment.getIdempotencyKey()))
            .toList();
        assertEquals(1, matching.size());
        assertEquals(1, outboxEventRepository
            .findOutboxEventsByAggregateId(paymentId).size());
    }

    private PaymentCreationResult createConcurrently(String key,
        CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent creation did not start");
        }
        return paymentService.createPayment(
            senderUser.getUserId(), sender.getId(), receiver.getId(),
            new BigDecimal("15.00"), "AUD", "concurrent", payeeCheckId,
            false, key
        );
    }

    @AfterEach
    void cleanUp() {
        if (paymentId != null) {
            jdbcTemplate.update(
                "delete from payment_reservations where payment_id = ?",
                paymentId
            );
            jdbcTemplate.update(
                "delete from api_idempotency_records where payment_id = ?",
                paymentId
            );
            jdbcTemplate.update(
                "delete from outbox_events where aggregate_id = ?",
                paymentId
            );
            jdbcTemplate.update("delete from payments where id = ?", paymentId);
        }
        if (payeeCheckId != null) {
            jdbcTemplate.update("delete from payee_checks where id = ?", payeeCheckId);
        }
        if (receiver != null) {
            jdbcTemplate.update(
                "delete from payee_registry_entries where receiver_wallet_id = ?",
                receiver.getId()
            );
        }
        if (sender != null) {
            jdbcTemplate.update("delete from wallets where id = ?", sender.getId());
        }
        if (receiver != null) {
            jdbcTemplate.update("delete from wallets where id = ?", receiver.getId());
        }
        if (senderUser != null) {
            jdbcTemplate.update(
                "delete from users where user_id = ?",
                senderUser.getUserId()
            );
        }
        if (receiverUser != null) {
            jdbcTemplate.update(
                "delete from users where user_id = ?",
                receiverUser.getUserId()
            );
        }
    }
}
