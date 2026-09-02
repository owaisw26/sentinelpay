package com.sentinelpay.payments.controller;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sentinelpay.payments.controller.request.PaymentRequest;
import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.service.PaymentService;


@RestController
@RequestMapping("/payments")
public class PaymentController {
    private PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping()
    public Payment createPayment(
        @RequestHeader("Idempotency-key") UUID idempotencyKey,
        @RequestBody PaymentRequest paymentRequest, Authentication authentication) {
            UUID userId = UUID.fromString(authentication.getName());

            Payment payment = paymentService.createPayment(userId, paymentRequest.senderWallet(), paymentRequest.receiverWallet(), paymentRequest.amount(), paymentRequest.currency(), paymentRequest.reference(), idempotencyKey);

            return payment;
    }

    @GetMapping("/{id}")
    public Payment getPayment(
        @PathVariable UUID id,
        Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());

        return paymentService.fetchPayment(userId, id);
    }
}
