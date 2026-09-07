package com.sentinelpay.payments.exception;

public class ReconciliationNotFoundException extends RuntimeException {
    public ReconciliationNotFoundException() {
        super("Reconciliation discrepancy not found");
    }
}
