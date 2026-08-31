package com.sentinelpay.payments.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.exception.PaymentAlreadyExistsException;
import com.sentinelpay.payments.exception.PaymentNotFoundException;
import com.sentinelpay.payments.exception.PaymentUnauthorizedAccess;
import com.sentinelpay.payments.exception.WalletUnauthorizedAccess;
import com.sentinelpay.payments.repository.PaymentRepository;

@Service
public class PaymentService {
    private PaymentRepository paymentRepository;

    private WalletService walletService;

    public PaymentService(PaymentRepository paymentRepository, WalletService walletService) {
        this.paymentRepository = paymentRepository;
        this.walletService = walletService;
    }

    // responsible for creating the payment
    public Payment createPayment(UUID userId, UUID senderWalletId, UUID receiverWalletId, BigDecimal amount, String currency, String reference, UUID idempotency_key) {
        Wallet sender = walletService.getWallet(senderWalletId);
        Wallet receiver = walletService.getWallet(receiverWalletId);
        
        // verify the user creating the payment owns this
        if (!sender.getUser().getUserId().equals(userId)) {
            throw new WalletUnauthorizedAccess(userId);
        }

        String requestContent = createRequestContent(
            userId,
            sender,
            receiver,
            amount,
            currency,
            reference
        );
        String requestHash = hashContent(requestContent);

        // verify this payment doesn't exist already
        Payment potentialPayment = paymentRepository.findByIdempotencyKey(idempotency_key).orElse(null);

        if (potentialPayment != null) {
            if (!potentialPayment.getRequestHash().equals(requestHash)) {
                throw new PaymentAlreadyExistsException();
            }
            return potentialPayment;
        }

        UUID paymentId = UUID.randomUUID();

        // user owns the sender wallet, can create payment
        Payment payment = new Payment(paymentId, 1, sender, receiver, amount, currency, reference, PaymentStatus.CREATED, idempotency_key, requestHash, LocalDateTime.now(), LocalDateTime.now());

        paymentRepository.save(payment);

        return payment;
    }

    public Payment fetchPayment(UUID userId, UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        UUID initiatingUserId = payment.getSenderWallet().getUser().getUserId();
        if (!initiatingUserId.equals(userId)) {
            throw new PaymentUnauthorizedAccess(userId, paymentId);
        }

        return payment;
    }

    private String createRequestContent(
        UUID userId,
        Wallet sender,
        Wallet receiver,
        BigDecimal amount,
        String currency,
        String reference
    ) {
        return String.join(
            "\u001f",
            userId.toString(),
            sender.getId().toString(),
            receiver.getId().toString(),
            amount.stripTrailingZeros().toPlainString(),
            currency.trim().toUpperCase(Locale.ROOT),
            reference
        );
    }

    private String hashContent(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
