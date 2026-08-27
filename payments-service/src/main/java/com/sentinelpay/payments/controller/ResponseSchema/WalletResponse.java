package com.sentinelpay.payments.controller.ResponseSchema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record WalletResponse(
    UUID walletId,
    UUID userId,
    String currency, 
    BigDecimal balance,
    LocalDateTime createdAt
) {
    
}
