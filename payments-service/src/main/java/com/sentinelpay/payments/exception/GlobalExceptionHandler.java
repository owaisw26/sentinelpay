package com.sentinelpay.payments.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(WalletNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String,String> handleWalletNotFound(WalletNotFoundException exception) {
        return Map.of(
            "error", "WALLET_NOT_FOUND",
            "message", exception.getMessage()
        );
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String,String> handleUserNotFound(UserNotFoundException exception) {
        return Map.of(
            "error", "USER_NOT_FOUND",
            "message", exception.getMessage()
        );
    }

    @ExceptionHandler(WalletUnauthorizedAccess.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String,String> handleWalletUnauthorized(WalletUnauthorizedAccess exception) {
        return Map.of(
            "error", "WALLET_ACCESS_DENIED",
            "message", exception.getMessage()
        );
    }

    @ExceptionHandler(WalletInsufficientBalanceException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public Map<String, String> handleWalletInsufficientBalance(WalletInsufficientBalanceException exception) {
        return Map.of(
            "error", "WALLET_INSUFFICIENT_BALANCE",
            "message", exception.getMessage()
        );
    }

    @ExceptionHandler(InvalidTransferException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public Map<String, String> handleWalletsHaveDifferentCurrencyException(InvalidTransferException exception) {
        return Map.of(
            "error", "DIFFERENT_WALLET_CURRENCY_NOT_ALLOWED",
            "message", exception.getMessage()
        );
    }

    @ExceptionHandler(PaymentAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handlePaymentAlreadyExists(PaymentAlreadyExistsException exception) {
        return Map.of(
            "error", "PAYMENT_ALREADY_EXISTS",
            "message", exception.getMessage()
        );
    }

    @ExceptionHandler(InvalidPaymentTransition.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handlePaymentAlreadyExists(InvalidPaymentTransition exception) {
        return Map.of(
            "error", "INVALID_PAYMENT_TRANSITION",
            "message", exception.getMessage()
        );
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handlePaymentNotFound(PaymentNotFoundException exception) {
        return Map.of(
            "error", "PAYMENT_NOT_FOUND",
            "message", exception.getMessage()
        );
    }

    @ExceptionHandler(PaymentUnauthorizedAccess.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> handlePaymentUnauthorized(PaymentUnauthorizedAccess exception) {
        return Map.of(
            "error", "PAYMENT_ACCESS_DENIED",
            "message", exception.getMessage()
        );
    }
}
