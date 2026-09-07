package com.sentinelpay.payments.service.reconciliation;

import com.sentinelpay.payments.domain.ReconciliationAction;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancyType;

record ReconciliationObservation(
    ReconciliationDiscrepancyType type,
    ReconciliationAction recommendedAction
) {}
