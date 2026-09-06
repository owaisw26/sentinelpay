package com.sentinelpay.payments.controller.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record WalletResponse(
    UUID walletId,
    UUID userId,
    String currency, 
    BigDecimal balance,
    BigDecimal reservedBalance,
    BigDecimal availableBalance,
    LocalDateTime createdAt
) {
    
}
