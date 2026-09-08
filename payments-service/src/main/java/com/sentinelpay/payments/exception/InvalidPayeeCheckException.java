package com.sentinelpay.payments.exception;

public class InvalidPayeeCheckException extends RuntimeException {
    private final String errorCode;

    private InvalidPayeeCheckException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static InvalidPayeeCheckException invalid() {
        return new InvalidPayeeCheckException(
            "PAYEE_CHECK_INVALID",
            "Payee check is invalid, expired, or already used"
        );
    }

    public static InvalidPayeeCheckException mismatchNotAccepted() {
        return new InvalidPayeeCheckException(
            "PAYEE_NAME_MISMATCH_NOT_ACCEPTED",
            "Name mismatch must be explicitly accepted"
        );
    }

    public String getErrorCode() {
        return errorCode;
    }
}
