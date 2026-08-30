package com.sentinelpay.payments.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class WalletInsufficientBalanceException extends RuntimeException {
    public WalletInsufficientBalanceException(UUID walletId, BigDecimal balance) {
        super("Wallet " + walletId + " has insufficient balance" + balance);
    }
}
