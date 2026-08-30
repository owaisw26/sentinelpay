package com.sentinelpay.payments.controller;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sentinelpay.payments.controller.RequestSchema.TransferRequest;
import com.sentinelpay.payments.controller.RequestSchema.WalletRequest;
import com.sentinelpay.payments.controller.ResponseSchema.WalletResponse;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.service.LedgerService;
import com.sentinelpay.payments.service.WalletService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/wallets")
public class WalletController {
    private final WalletService walletService;
    private final LedgerService ledgerService;

    public WalletController(WalletService walletService,
        LedgerService ledgerService
    ) {
        this.walletService = walletService;
        this.ledgerService = ledgerService;
    }

    @PostMapping
    public WalletResponse createWallet(
        @Valid @RequestBody WalletRequest walletRequest,
        Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        Wallet wallet =  walletService.createWallet(
                                          userId,
                                          walletRequest.currency());

        return new WalletResponse(wallet.getId(), 
                                  wallet.getUser().getUserId(), 
                                  wallet.getCurrency(), 
                                  wallet.getBalance(), 
                                  wallet.getCreatedAt());
        }

    @GetMapping("/{walletId}")
    public WalletResponse getWallet(
        @PathVariable UUID walletId,
        Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        Wallet wallet = walletService.fetchWallet(walletId, userId);
        return new WalletResponse(wallet.getId(), 
                                  wallet.getUser().getUserId(), 
                                  wallet.getCurrency(), 
                                  wallet.getBalance(), 
                                  wallet.getCreatedAt());
    }

    @PostMapping("/transfer")
    public WalletResponse transferAmount(
        @Valid @RequestBody TransferRequest request,
        Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        Wallet wallet = ledgerService.transfer(userId, request.senderWallet(), request.receiverWallet(), request.amount(), request.reference());
        return new WalletResponse(wallet.getId(), 
                                  wallet.getUser().getUserId(), 
                                  wallet.getCurrency(), 
                                  wallet.getBalance(), 
                                  wallet.getCreatedAt());
    }
}
