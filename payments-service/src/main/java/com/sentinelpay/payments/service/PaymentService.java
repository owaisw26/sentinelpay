package com.sentinelpay.payments.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.sentinelpay.payments.controller.response.PaymentResponse;
import com.sentinelpay.payments.domain.OutboxEvent;
import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.exception.InvalidPaymentRequestException;
import com.sentinelpay.payments.exception.PaymentAlreadyExistsException;
import com.sentinelpay.payments.exception.PaymentNotFoundException;
import com.sentinelpay.payments.exception.WalletNotFoundException;
import com.sentinelpay.payments.repository.ApiIdempotencyRepository;
import com.sentinelpay.payments.repository.ApiIdempotencyRepository.StoredResponse;
import com.sentinelpay.payments.repository.OutboxEventRepository;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.service.outbox.PaymentCreatedEvent;

import jakarta.transaction.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PaymentService {
    private static final String CREATE_PAYMENT_ROUTE = "POST:/payments";

    private final PaymentRepository paymentRepository;
    private final WalletService walletService;
    private final OutboxEventRepository outboxEventRepository;
    private final ApiIdempotencyRepository idempotencyRepository;
    private final PayeeCheckService payeeCheckService;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository paymentRepository,
        WalletService walletService,
        OutboxEventRepository outboxEventRepository,
        ApiIdempotencyRepository idempotencyRepository,
        PayeeCheckService payeeCheckService,
        ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.walletService = walletService;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.payeeCheckService = payeeCheckService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaymentCreationResult createPayment(UUID userId,
        UUID senderWalletId, UUID receiverWalletId, BigDecimal amount,
        String currency, String reference, UUID payeeCheckId,
        boolean acceptNameMismatch, String idempotencyKey) {
        validateInput(senderWalletId, receiverWalletId, amount, currency,
            reference, payeeCheckId, idempotencyKey);

        String requestHash = hashContent(String.join(
            "\u001f",
            userId.toString(),
            senderWalletId.toString(),
            receiverWalletId.toString(),
            amount.stripTrailingZeros().toPlainString(),
            currency,
            reference,
            payeeCheckId.toString(),
            Boolean.toString(acceptNameMismatch)
        ));
        LocalDateTime now = LocalDateTime.now();

        boolean claimed = idempotencyRepository.claim(
            userId, CREATE_PAYMENT_ROUTE, idempotencyKey, requestHash, now
        );
        if (!claimed) {
            return replay(userId, idempotencyKey, requestHash);
        }

        Wallet sender = walletService.getWallet(senderWalletId);
        Wallet receiver = walletService.getWallet(receiverWalletId);

        if (!sender.getUser().getUserId().equals(userId)) {
            throw new WalletNotFoundException(senderWalletId);
        }
        validateWalletCurrencies(sender, receiver);
        var payeeCheck = payeeCheckService.authorizeForPayment(
            payeeCheckId, userId, receiverWalletId, acceptNameMismatch
        );

        Payment payment = new Payment(
            UUID.randomUUID(), 0, sender, receiver, amount, currency,
            reference, PaymentStatus.CREATED, idempotencyKey, requestHash,
            now, now
        );
        payment.attachPayeeCheck(
            payeeCheck.getId(), payeeCheck.isMismatch()
        );
        payment = paymentRepository.saveAndFlush(payment);

        PaymentCreatedEvent payloadObject = new PaymentCreatedEvent(
            payment.getId(), senderWalletId, receiverWalletId, amount, currency
        );
        JsonNode payload = objectMapper.valueToTree(payloadObject);
        outboxEventRepository.save(new OutboxEvent(
            payment.getId(), "PAYMENT_CREATED", payload, UUID.randomUUID()
        ));

        PaymentResponse response = PaymentResponse.from(payment);
        idempotencyRepository.complete(
            userId, CREATE_PAYMENT_ROUTE, idempotencyKey, payment.getId(),
            HttpStatus.CREATED.value(), objectMapper.writeValueAsString(response),
            now
        );
        return new PaymentCreationResult(payment, response, false);
    }

    // Internal convenience for asynchronous component tests.
    @Transactional
    public Payment createPayment(UUID userId, UUID senderWalletId,
        UUID receiverWalletId, BigDecimal amount, String currency,
        String reference, UUID idempotencyKey) {
        Wallet receiver = walletService.getWallet(receiverWalletId);
        var check = payeeCheckService.createCheck(
            userId, receiverWalletId, receiver.getUser().getName()
        );
        return createPayment(userId, senderWalletId, receiverWalletId, amount,
            currency, reference, check.getId(), false,
            idempotencyKey.toString()).payment();
    }

    @Transactional
    public PaymentResponse fetchPayment(UUID userId, UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        UUID initiatingUserId = payment.getSenderWallet().getUser().getUserId();
        if (!initiatingUserId.equals(userId)) {
            throw new PaymentNotFoundException(paymentId);
        }
        return PaymentResponse.from(payment);
    }

    private PaymentCreationResult replay(UUID userId, String idempotencyKey,
        String requestHash) {
        StoredResponse stored = idempotencyRepository.find(
            userId, CREATE_PAYMENT_ROUTE, idempotencyKey
        ).orElseThrow(() -> new IllegalStateException(
            "Idempotency claim disappeared"
        ));

        if (!MessageDigest.isEqual(
            stored.requestHash().getBytes(StandardCharsets.UTF_8),
            requestHash.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new PaymentAlreadyExistsException();
        }
        if (!stored.isComplete()) {
            throw new IllegalStateException("Idempotent response is incomplete");
        }

        Payment payment = paymentRepository.findById(stored.paymentId())
            .orElseThrow(() -> new IllegalStateException(
                "Idempotent payment is missing"
            ));
        PaymentResponse response = objectMapper.readValue(
            stored.responseBody(), PaymentResponse.class
        );
        return new PaymentCreationResult(payment, response, true);
    }

    private void validateInput(UUID senderWalletId, UUID receiverWalletId,
        BigDecimal amount, String currency, String reference,
        UUID payeeCheckId, String idempotencyKey) {
        if (senderWalletId == null || receiverWalletId == null) {
            throw new InvalidPaymentRequestException("Wallet IDs are required");
        }
        if (senderWalletId.equals(receiverWalletId)) {
            throw new InvalidPaymentRequestException(
                "Sender and receiver wallets must be different"
            );
        }
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidPaymentRequestException("Amount must be positive");
        }
        int integerDigits = Math.max(0, amount.precision() - amount.scale());
        if (amount.scale() > 2 || integerDigits > 17) {
            throw new InvalidPaymentRequestException(
                "Amount must have at most 17 integer and 2 fractional digits"
            );
        }
        if (!"AUD".equals(currency)) {
            throw new InvalidPaymentRequestException(
                "Only AUD payments are supported"
            );
        }
        if (reference == null || reference.isBlank() ||
            reference.length() > 140) {
            throw new InvalidPaymentRequestException(
                "Reference must contain between 1 and 140 characters"
            );
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() ||
            idempotencyKey.length() > 128) {
            throw new InvalidPaymentRequestException(
                "Idempotency-Key must contain between 1 and 128 characters"
            );
        }
        if (payeeCheckId == null) {
            throw new InvalidPaymentRequestException("Payee check ID is required");
        }
    }

    private void validateWalletCurrencies(Wallet sender, Wallet receiver) {
        if (!"AUD".equals(sender.getCurrency()) ||
            !"AUD".equals(receiver.getCurrency())) {
            throw new InvalidPaymentRequestException(
                "Both wallets must use AUD"
            );
        }
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
