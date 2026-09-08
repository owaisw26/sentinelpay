package com.sentinelpay.payments.controller;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sentinelpay.payments.controller.request.PaymentRequest;
import com.sentinelpay.payments.controller.response.PaymentResponse;
import com.sentinelpay.payments.service.PaymentCreationResult;
import com.sentinelpay.payments.service.PaymentService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;


@RestController
@RequestMapping("/payments")
@Validated
public class PaymentController {
    private PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping()
    public ResponseEntity<PaymentResponse> createPayment(
        @RequestHeader("Idempotency-Key")
        @NotBlank @Size(max = 128) String idempotencyKey,
        @Valid @RequestBody PaymentRequest paymentRequest,
        Authentication authentication) {
            UUID userId = UUID.fromString(authentication.getName());

            PaymentCreationResult result = paymentService.createPayment(
                userId,
                paymentRequest.senderWalletId(),
                paymentRequest.receiverWalletId(),
                paymentRequest.amount(),
                paymentRequest.currency(),
                paymentRequest.reference(),
                paymentRequest.payeeCheckId(),
                paymentRequest.acceptNameMismatch(),
                idempotencyKey
            );

            ResponseEntity.BodyBuilder response = ResponseEntity
                .status(HttpStatus.CREATED);
            if (result.replayed()) {
                response.header("Idempotency-Replayed", "true");
            }
            return response.body(result.response());
    }

    @GetMapping("/{id}")
    public PaymentResponse getPayment(
        @PathVariable UUID id,
        Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());

        return paymentService.fetchPayment(userId, id);
    }
}
