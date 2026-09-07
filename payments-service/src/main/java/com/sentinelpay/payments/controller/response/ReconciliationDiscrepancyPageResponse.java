package com.sentinelpay.payments.controller.response;

import java.util.List;

public record ReconciliationDiscrepancyPageResponse(
    List<ReconciliationDiscrepancyResponse> items,
    String nextCursor
) {}
