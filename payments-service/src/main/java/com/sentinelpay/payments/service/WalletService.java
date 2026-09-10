package com.sentinelpay.payments.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.exception.UserNotFoundException;
import com.sentinelpay.payments.exception.InvalidPaymentRequestException;
import com.sentinelpay.payments.exception.WalletNotFoundException;
import com.sentinelpay.payments.repository.UserRepository;
import com.sentinelpay.payments.repository.WalletRepository;


@Service
public class WalletService {
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    public WalletService(WalletRepository walletRepository, 
                        UserRepository userRepository) {
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
    }

    public Wallet createWallet(UUID userId, String currency) {
        if (!"AUD".equals(currency)) {
            throw new InvalidPaymentRequestException(
                "Only AUD wallets are supported"
            );
        }
        User user = userRepository.findById(userId).orElseThrow(() -> 
        new UserNotFoundException());

        UUID walletId = UUID.randomUUID();

        Wallet wallet = new Wallet(walletId, 
                                   user, 
                                   currency, 
                                   BigDecimal.ZERO, 
                                   LocalDateTime.now());

        return walletRepository.save(wallet);
    }

    public Wallet fetchWallet(UUID walletId, UUID userId) {
        Wallet wallet = walletRepository.findById(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
        
        // verify the wallet belongs to the user
        UUID walletUserId = wallet.getUser().getUserId();
        if (userId.equals(walletUserId)) {
            return wallet;
        } else {
            throw new WalletNotFoundException(walletId);
        }
    }

    public List<Wallet> listWallets(UUID userId) {
        return walletRepository.findByUserUserIdOrderByCreatedAtAsc(userId);
    }

    public Wallet getWallet(UUID walletId) {
        Wallet wallet = walletRepository.findById(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
        return wallet;
    }
}
