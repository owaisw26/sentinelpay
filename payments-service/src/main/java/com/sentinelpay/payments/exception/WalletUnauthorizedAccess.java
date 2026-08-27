package com.sentinelpay.payments.exception;

import java.util.UUID;

public class WalletUnauthorizedAccess extends RuntimeException {
    public WalletUnauthorizedAccess(UUID userId) {
        super("User " + userId + " does not have access to the wallet");
    }
}
