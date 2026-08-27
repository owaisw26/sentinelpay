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
}
