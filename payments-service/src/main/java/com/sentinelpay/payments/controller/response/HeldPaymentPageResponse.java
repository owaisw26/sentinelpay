package com.sentinelpay.payments.controller.response;

import java.util.List;

public record HeldPaymentPageResponse(
    List<HeldPaymentResponse> items,
    String nextCursor
) {}
