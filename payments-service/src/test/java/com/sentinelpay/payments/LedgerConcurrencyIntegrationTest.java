package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.exception.WalletInsufficientBalanceException;
import com.sentinelpay.payments.repository.WalletRepository;
import com.sentinelpay.payments.service.PaymentService;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;

@SpringBootTest
class LedgerConcurrencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentPaymentCreationCannotOverReserveOneWallet()
        throws Exception {
        User senderUser = userService.createCustomer("Reservation Sender");
        User receiverOneUser = userService.createCustomer("Receiver One");
        User receiverTwoUser = userService.createCustomer("Receiver Two");
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiverOne = walletService.createWallet(
            receiverOneUser.getUserId(), "AUD"
        );
        Wallet receiverTwo = walletService.createWallet(
            receiverTwoUser.getUserId(), "AUD"
        );
        sender.setBalance(new BigDecimal("100.00"));
        walletRepository.saveAndFlush(sender);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> first = () -> create(
            senderUser, sender, receiverOne, "reserve-one", ready, start
        );
        Callable<Boolean> second = () -> create(
            senderUser, sender, receiverTwo, "reserve-two", ready, start
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Boolean> outcomes;
        try {
            Future<Boolean> firstResult = executor.submit(first);
            Future<Boolean> secondResult = executor.submit(second);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            outcomes = List.of(
                firstResult.get(10, TimeUnit.SECONDS),
                secondResult.get(10, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
        }

        BigDecimal balance = jdbcTemplate.queryForObject(
            "select balance from wallets where id = ?", BigDecimal.class,
            sender.getId()
        );
        BigDecimal reserved = jdbcTemplate.queryForObject(
            "select reserved_balance from wallets where id = ?",
            BigDecimal.class, sender.getId()
        );
        assertEquals(0, new BigDecimal("100.00").compareTo(balance));
        assertEquals(0, new BigDecimal("75.00")
            .compareTo(reserved));
        assertEquals(0, new BigDecimal("25.00")
            .compareTo(balance.subtract(reserved)));
        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from payment_reservations " +
                "where wallet_id = ? and status = 'ACTIVE'",
            Integer.class, sender.getId()
        ));
        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from payments where sender_wallet_id = ?",
            Integer.class, sender.getId()
        ));
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
    }

    private boolean create(User senderUser, Wallet sender, Wallet receiver,
        String reference, CountDownLatch ready, CountDownLatch start)
        throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent creation did not start");
        }
        try {
            paymentService.createPayment(
                senderUser.getUserId(), sender.getId(), receiver.getId(),
                new BigDecimal("75.00"), "AUD", reference, UUID.randomUUID()
            );
            return true;
        } catch (WalletInsufficientBalanceException exception) {
            return false;
        }
    }
}
