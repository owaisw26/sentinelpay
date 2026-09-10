package com.sentinelpay.payments.config;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.repository.UserRepository;
import com.sentinelpay.payments.repository.WalletRepository;

@Component
@Profile("local")
public class LocalDemoDataInitializer implements ApplicationRunner {
    public static final UUID CUSTOMER_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000001"
    );
    public static final UUID PAYEE_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000002"
    );
    public static final UUID ANALYST_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000003"
    );
    public static final UUID CUSTOMER_WALLET_ID = UUID.fromString(
        "20000000-0000-0000-0000-000000000001"
    );
    public static final UUID PAYEE_WALLET_ID = UUID.fromString(
        "20000000-0000-0000-0000-000000000002"
    );

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;

    public LocalDemoDataInitializer(
        UserRepository userRepository,
        WalletRepository walletRepository
    ) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        User customer = user(CUSTOMER_ID, "Avery Customer", "CUSTOMER");
        User payee = user(PAYEE_ID, "Morgan Lee", "CUSTOMER");
        user(ANALYST_ID, "Riley Analyst", "ANALYST");
        wallet(CUSTOMER_WALLET_ID, customer, new BigDecimal("12500.00"));
        wallet(PAYEE_WALLET_ID, payee, new BigDecimal("3200.00"));
    }

    private User user(UUID id, String name, String role) {
        return userRepository.findById(id).orElseGet(() ->
            userRepository.save(new User(id, name, role, LocalDateTime.now()))
        );
    }

    private void wallet(UUID id, User user, BigDecimal openingBalance) {
        if (walletRepository.existsById(id)) {
            return;
        }
        walletRepository.save(new Wallet(
            id, user, "AUD", openingBalance, LocalDateTime.now()
        ));
    }
}
