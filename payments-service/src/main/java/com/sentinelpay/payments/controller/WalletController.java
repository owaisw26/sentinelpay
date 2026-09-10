package com.sentinelpay.payments.controller;

import java.util.UUID;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sentinelpay.payments.controller.request.WalletRequest;
import com.sentinelpay.payments.controller.response.WalletResponse;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.service.WalletService;
import com.sentinelpay.payments.security.AuthenticatedUserResolver;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/wallets")
public class WalletController {
    private final WalletService walletService;
    private final AuthenticatedUserResolver authenticatedUsers;

    public WalletController(WalletService walletService,
        AuthenticatedUserResolver authenticatedUsers) {
        this.walletService = walletService;
        this.authenticatedUsers = authenticatedUsers;
    }

    @GetMapping
    public List<WalletResponse> listWallets(Authentication authentication) {
        UUID userId = authenticatedUsers.resolve(authentication).getUserId();
        return walletService.listWallets(userId).stream()
            .map(wallet -> new WalletResponse(
                wallet.getId(), wallet.getUser().getUserId(),
                wallet.getCurrency(), wallet.getBalance(),
                wallet.getReservedBalance(), wallet.getAvailableBalance(),
                wallet.getCreatedAt()
            ))
            .toList();
    }

    @PostMapping
    public WalletResponse createWallet(
        @Valid @RequestBody WalletRequest walletRequest,
        Authentication authentication
    ) {
        UUID userId = authenticatedUsers.resolve(authentication).getUserId();
        Wallet wallet =  walletService.createWallet(
                                          userId,
                                          walletRequest.currency());

        return new WalletResponse(wallet.getId(), 
                                  wallet.getUser().getUserId(), 
                                  wallet.getCurrency(), 
                                  wallet.getBalance(),
                                  wallet.getReservedBalance(),
                                  wallet.getAvailableBalance(),
                                  wallet.getCreatedAt());
        }

    @GetMapping("/{walletId}")
    public WalletResponse getWallet(
        @PathVariable UUID walletId,
        Authentication authentication
    ) {
        UUID userId = authenticatedUsers.resolve(authentication).getUserId();
        Wallet wallet = walletService.fetchWallet(walletId, userId);
        return new WalletResponse(wallet.getId(), 
                                  wallet.getUser().getUserId(), 
                                  wallet.getCurrency(), 
                                  wallet.getBalance(),
                                  wallet.getReservedBalance(),
                                  wallet.getAvailableBalance(),
                                  wallet.getCreatedAt());
    }
}
