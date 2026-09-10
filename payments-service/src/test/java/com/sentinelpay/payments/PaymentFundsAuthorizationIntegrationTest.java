package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.exception.WalletInsufficientBalanceException;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.PaymentReservationRepository;
import com.sentinelpay.payments.repository.WalletRepository;
import com.sentinelpay.payments.service.PaymentService;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;

@SpringBootTest
class PaymentFundsAuthorizationIntegrationTest extends AbstractIntegrationTest {
    @Autowired private UserService userService;
    @Autowired private WalletService walletService;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentReservationRepository reservationRepository;
    @Autowired private WalletRepository walletRepository;

    @Test
    void insufficientBalanceRollsBackBeforePaymentEntersScreening() {
        User senderUser = userService.createCustomer("Authorization sender");
        User receiverUser = userService.createCustomer("Authorization receiver");
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        sender.setBalance(new BigDecimal("100.00"));
        walletRepository.saveAndFlush(sender);
        long paymentCount = paymentRepository.count();
        long reservationCount = reservationRepository.count();

        assertThrows(WalletInsufficientBalanceException.class, () ->
            paymentService.createPayment(
                senderUser.getUserId(), sender.getId(), receiver.getId(),
                new BigDecimal("100.01"), "AUD", "insufficient funds",
                UUID.randomUUID()
            )
        );

        assertEquals(paymentCount, paymentRepository.count());
        assertEquals(reservationCount, reservationRepository.count());
        Wallet unchanged = walletRepository.findById(sender.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("100.00").compareTo(unchanged.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(unchanged.getReservedBalance()));
    }
}
