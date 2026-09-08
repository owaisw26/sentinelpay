package com.sentinelpay.payments.exception;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler({
        WalletNotFoundException.class,
        WalletUnauthorizedAccess.class
    })
    public ProblemDetail handleWalletNotFound(RuntimeException exception) {
        return problem(
            HttpStatus.NOT_FOUND,
            "WALLET_NOT_FOUND",
            "Wallet not found"
        );
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
    }

    @ExceptionHandler(WalletInsufficientBalanceException.class)
    public ProblemDetail handleInsufficientBalance(
        WalletInsufficientBalanceException exception
    ) {
        return problem(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "WALLET_INSUFFICIENT_BALANCE",
            "Insufficient available balance"
        );
    }

    @ExceptionHandler(InvalidTransferException.class)
    public ProblemDetail handleInvalidTransfer(InvalidTransferException exception) {
        return problem(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INVALID_TRANSFER",
            exception.getMessage()
        );
    }

    @ExceptionHandler(InvalidPaymentRequestException.class)
    public ProblemDetail handleInvalidPaymentRequest(
        InvalidPaymentRequestException exception
    ) {
        return problem(
            HttpStatus.BAD_REQUEST,
            "INVALID_PAYMENT_REQUEST",
            exception.getMessage()
        );
    }

    @ExceptionHandler(InvalidPayeeCheckException.class)
    public ProblemDetail handleInvalidPayeeCheck(
        InvalidPayeeCheckException exception
    ) {
        return problem(
            HttpStatus.UNPROCESSABLE_CONTENT,
            exception.getErrorCode(),
            exception.getMessage()
        );
    }

    @ExceptionHandler(PayeeVerificationUnavailableException.class)
    public ProblemDetail handlePayeeVerificationUnavailable(
        PayeeVerificationUnavailableException exception
    ) {
        return problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "PAYEE_VERIFICATION_UNAVAILABLE",
            exception.getMessage()
        );
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(
        RateLimitExceededException exception
    ) {
        ProblemDetail detail = problem(
            HttpStatus.TOO_MANY_REQUESTS,
            "RATE_LIMIT_EXCEEDED",
            "Too many requests"
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(
                HttpHeaders.RETRY_AFTER,
                Long.toString(exception.getRetryAfterSeconds())
            )
            .body(detail);
    }

    @ExceptionHandler(PaymentAlreadyExistsException.class)
    public ProblemDetail handlePaymentAlreadyExists(
        PaymentAlreadyExistsException exception
    ) {
        return problem(
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_KEY_REUSED",
            "Idempotency-Key was already used with a different request"
        );
    }

    @ExceptionHandler(InvalidPaymentTransition.class)
    public ProblemDetail handleInvalidTransition(InvalidPaymentTransition exception) {
        return problem(
            HttpStatus.CONFLICT,
            "INVALID_PAYMENT_TRANSITION",
            exception.getMessage()
        );
    }

    @ExceptionHandler({
        PaymentNotFoundException.class,
        PaymentUnauthorizedAccess.class
    })
    public ProblemDetail handlePaymentNotFound(RuntimeException exception) {
        return problem(
            HttpStatus.NOT_FOUND,
            "PAYMENT_NOT_FOUND",
            "Payment not found"
        );
    }

    @ExceptionHandler(InvalidWebhookSignatureException.class)
    public ProblemDetail handleInvalidWebhookSignature(
        InvalidWebhookSignatureException exception
    ) {
        return problem(
            HttpStatus.UNAUTHORIZED,
            "INVALID_WEBHOOK_SIGNATURE",
            "Webhook signature is invalid or expired"
        );
    }

    @ExceptionHandler(InvalidWebhookPayloadException.class)
    public ProblemDetail handleInvalidWebhookPayload(
        InvalidWebhookPayloadException exception
    ) {
        return problem(
            HttpStatus.BAD_REQUEST,
            "INVALID_WEBHOOK_PAYLOAD",
            exception.getMessage()
        );
    }

    @ExceptionHandler(ConflictingWebhookEventException.class)
    public ProblemDetail handleConflictingWebhookEvent(
        ConflictingWebhookEventException exception
    ) {
        return problem(
            HttpStatus.CONFLICT,
            "CONFLICTING_WEBHOOK_EVENT",
            exception.getMessage()
        );
    }

    @ExceptionHandler(ReconciliationNotFoundException.class)
    public ProblemDetail handleReconciliationNotFound(
        ReconciliationNotFoundException exception
    ) {
        return problem(
            HttpStatus.NOT_FOUND,
            "RECONCILIATION_DISCREPANCY_NOT_FOUND",
            exception.getMessage()
        );
    }

    @ExceptionHandler(ReconciliationConflictException.class)
    public ProblemDetail handleReconciliationConflict(
        ReconciliationConflictException exception
    ) {
        return problem(
            HttpStatus.CONFLICT,
            "RECONCILIATION_CONFLICT",
            exception.getMessage()
        );
    }

    @ExceptionHandler(InvalidReconciliationRequestException.class)
    public ProblemDetail handleInvalidReconciliationRequest(
        InvalidReconciliationRequestException exception
    ) {
        return problem(
            HttpStatus.BAD_REQUEST,
            "INVALID_RECONCILIATION_REQUEST",
            exception.getMessage()
        );
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException exception,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .orElse("Request body is invalid");
        return new ResponseEntity<>(
            problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail),
            headers,
            status
        );
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
        HandlerMethodValidationException exception,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        return new ResponseEntity<>(
            problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request header or path parameter is invalid"
            ),
            headers,
            status
        );
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
        HttpMessageNotReadableException exception,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        return new ResponseEntity<>(
            problem(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "Request body is malformed"
            ),
            headers,
            status
        );
    }

    private ProblemDetail problem(
        HttpStatus status,
        String errorCode,
        String detail
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create(
            "urn:sentinelpay:problem:" + errorCode.toLowerCase()
                .replace('_', '-')
        ));
        problem.setProperty("errorCode", errorCode);
        // Retained as a compatibility extension while clients move to
        // errorCode and the standard RFC 7807 fields.
        problem.setProperty("error", errorCode);
        return problem;
    }
}
