package com.sentinelpay.payments.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sentinelpay.payments.provider.PaymentProviderWebhook;
import com.sentinelpay.payments.security.WebhookSignatureVerifier;
import com.sentinelpay.payments.service.PaymentWebhookProcessor;

import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/webhooks/psp")
public class PaymentWebhookController {
    private final PaymentWebhookProcessor processor;
    private final WebhookSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;

    public PaymentWebhookController(
        PaymentWebhookProcessor processor,
        WebhookSignatureVerifier signatureVerifier,
        ObjectMapper objectMapper
    ) {
        this.processor = processor;
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(
        @RequestHeader("X-PSP-Timestamp") String timestamp,
        @RequestHeader("X-PSP-Signature") String signature,
        @RequestBody byte[] rawBody
    ) {
        signatureVerifier.verify(timestamp, signature, rawBody);
        PaymentProviderWebhook webhook = objectMapper.readValue(
            rawBody,
            PaymentProviderWebhook.class
        );
        processor.process(webhook);
    }
}
