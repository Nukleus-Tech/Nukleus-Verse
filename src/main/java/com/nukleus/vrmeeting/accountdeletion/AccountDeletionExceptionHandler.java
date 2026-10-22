package com.nukleus.vrmeeting.accountdeletion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = AccountDeletionController.class)
public class AccountDeletionExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AccountDeletionExceptionHandler.class);
    @ExceptionHandler(AccountDeletionException.class)
    ResponseEntity<AccountDeletionDtos.ErrorResponse> known(AccountDeletionException e) {
        return response(e.getStatus(), e.getCode());
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<AccountDeletionDtos.ErrorResponse> invalid(Exception ignored) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<AccountDeletionDtos.ErrorResponse> conflict(Exception ignored) {
        return response(HttpStatus.CONFLICT, "REQUEST_CONFLICT");
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<AccountDeletionDtos.ErrorResponse> unexpected(Exception e) {
        // Exception messages/stack traces may contain SQL parameters, signed URLs or PII.
        LOG.warn("Account deletion operation failed: {}", e.getClass().getSimpleName());
        return response(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_DELETION_UNAVAILABLE");
    }
    private ResponseEntity<AccountDeletionDtos.ErrorResponse> response(HttpStatus status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(new AccountDeletionDtos.ErrorResponse(code, code));
    }
}
