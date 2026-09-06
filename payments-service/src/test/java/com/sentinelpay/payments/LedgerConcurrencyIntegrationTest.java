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
import com.sentinelpay.payments.service.outbox.OutboxMessage;
import com.sentinelpay.payments.service.outbox.PaymentEventProcessor;

@SpringBootTest
class LedgerConcurrencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentEventProcessor paymentEventProcessor;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentReservationRepository reservationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Test
    void concurrentPendingPaymentsCannotOverReserveOneWallet()
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

        Payment firstPayment = paymentService.createPayment(
            senderUser.getUserId(), sender.getId(), receiverOne.getId(),
            new BigDecimal("75.00"), "AUD", "reserve-one",
            UUID.randomUUID()
        );
        Payment secondPayment = paymentService.createPayment(
            senderUser.getUserId(), sender.getId(), receiverTwo.getId(),
            new BigDecimal("75.00"), "AUD", "reserve-two",
            UUID.randomUUID()
        );
        OutboxMessage firstMessage = message(firstPayment);
        OutboxMessage secondMessage = message(secondPayment);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> first = () -> process(firstMessage, ready, start);
        Callable<Boolean> second = () -> process(secondMessage, ready, start);

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

        Wallet updated = walletRepository.findById(sender.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("100.00").compareTo(updated.getBalance()));
        assertEquals(0, new BigDecimal("75.00")
            .compareTo(updated.getReservedBalance()));
        assertEquals(0, new BigDecimal("25.00")
            .compareTo(updated.getAvailableBalance()));
        assertEquals(1, reservationRepository.findAll().stream()
            .filter(reservation -> reservation.getWallet().getId()
                .equals(sender.getId()))
            .filter(reservation -> reservation.getStatus() ==
                PaymentReservationStatus.ACTIVE)
            .count());
        assertEquals(1, List.of(firstPayment.getId(), secondPayment.getId())
            .stream()
            .map(id -> paymentRepository.findById(id).orElseThrow())
            .filter(payment -> payment.getStatus() == PaymentStatus.PROCESSING)
            .count());
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
    }

    private boolean process(OutboxMessage message, CountDownLatch ready,
        CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent processing did not start");
        }
        try {
            paymentEventProcessor.process(message);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private OutboxMessage message(Payment payment) {
        OutboxEvent event = outboxEventRepository
            .findOutboxEventsByAggregateId(payment.getId()).getFirst();
        return new OutboxMessage(
            event.getId(), event.getAggregateId(), event.getEventType(),
            event.getCorrelationId(), event.getCreatedAt(), event.getPayload()
        );
    }
}
