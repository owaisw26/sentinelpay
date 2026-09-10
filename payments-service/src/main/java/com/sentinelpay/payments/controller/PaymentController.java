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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sentinelpay.payments.controller.request.PaymentRequest;
import com.sentinelpay.payments.controller.response.PaymentResponse;
import com.sentinelpay.payments.service.PaymentCreationResult;
import com.sentinelpay.payments.service.PaymentService;
import com.sentinelpay.payments.service.PaymentQueryService;
import com.sentinelpay.payments.controller.response.PaymentPageResponse;
import com.sentinelpay.payments.service.RateLimitOperation;
import com.sentinelpay.payments.service.RateLimited;
import com.sentinelpay.payments.security.AuthenticatedUserResolver;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.validation.annotation.Validated;


@RestController
@RequestMapping("/payments")
@Validated
public class PaymentController {
    private PaymentService paymentService;
    private final AuthenticatedUserResolver authenticatedUsers;
    private final PaymentQueryService paymentQueries;

    public PaymentController(PaymentService paymentService,
        AuthenticatedUserResolver authenticatedUsers,
        PaymentQueryService paymentQueries) {
        this.paymentService = paymentService;
        this.authenticatedUsers = authenticatedUsers;
        this.paymentQueries = paymentQueries;
    }

    @GetMapping
    public PaymentPageResponse listPayments(
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
        Authentication authentication
    ) {
        UUID userId = authenticatedUsers.resolve(authentication).getUserId();
        return paymentQueries.listForCustomer(userId, cursor, limit);
    }

    @PostMapping()
    @RateLimited(RateLimitOperation.PAYMENT_CREATE)
    public ResponseEntity<PaymentResponse> createPayment(
        @RequestHeader("Idempotency-Key")
        @NotBlank @Size(max = 128) String idempotencyKey,
        @Valid @RequestBody PaymentRequest paymentRequest,
        Authentication authentication) {
            UUID userId = authenticatedUsers.resolve(authentication).getUserId();

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
        UUID userId = authenticatedUsers.resolve(authentication).getUserId();

        return paymentService.fetchPayment(userId, id);
    }
}
