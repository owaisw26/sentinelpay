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
import com.sentinelpay.payments.service.RateLimitOperation;
import com.sentinelpay.payments.service.RateLimited;
import com.sentinelpay.payments.security.AuthenticatedUserResolver;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/payee-checks")
public class PayeeCheckController {
    private final PayeeCheckService payeeCheckService;
    private final AuthenticatedUserResolver authenticatedUsers;

    public PayeeCheckController(PayeeCheckService payeeCheckService,
        AuthenticatedUserResolver authenticatedUsers) {
        this.payeeCheckService = payeeCheckService;
        this.authenticatedUsers = authenticatedUsers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(RateLimitOperation.PAYEE_CHECK)
    public PayeeCheckResponse createCheck(
        @Valid @RequestBody PayeeCheckRequest request,
        Authentication authentication
    ) {
        UUID userId = authenticatedUsers.resolve(authentication).getUserId();
        return PayeeCheckResponse.from(payeeCheckService.createCheck(
            userId, request.receiverWalletId(), request.suppliedName()
        ));
    }
}
