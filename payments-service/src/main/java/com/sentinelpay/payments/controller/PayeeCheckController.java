package com.sentinelpay.payments.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sentinelpay.payments.controller.request.PayeeCheckRequest;
import com.sentinelpay.payments.controller.response.PayeeCheckResponse;
import com.sentinelpay.payments.service.PayeeCheckService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/payee-checks")
public class PayeeCheckController {
    private final PayeeCheckService payeeCheckService;

    public PayeeCheckController(PayeeCheckService payeeCheckService) {
        this.payeeCheckService = payeeCheckService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PayeeCheckResponse createCheck(
        @Valid @RequestBody PayeeCheckRequest request,
        Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return PayeeCheckResponse.from(payeeCheckService.createCheck(
            userId, request.receiverWalletId(), request.suppliedName()
        ));
    }
}
