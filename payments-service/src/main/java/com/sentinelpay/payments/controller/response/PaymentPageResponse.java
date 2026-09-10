package com.sentinelpay.payments.controller.response;

import java.util.List;

public record PaymentPageResponse(
    List<PaymentResponse> items,
    String nextCursor
) {}
