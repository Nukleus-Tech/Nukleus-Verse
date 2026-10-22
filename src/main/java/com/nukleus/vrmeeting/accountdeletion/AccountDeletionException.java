package com.nukleus.vrmeeting.accountdeletion;

import org.springframework.http.HttpStatus;

public class AccountDeletionException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public AccountDeletionException(HttpStatus status, String code) {
        super(code); this.status = status; this.code = code;
    }
    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public static AccountDeletionException integration() {
        return new AccountDeletionException(HttpStatus.SERVICE_UNAVAILABLE, "INTEGRATION_REQUIRED");
    }
    static AccountDeletionException conflict(String code) {
        return new AccountDeletionException(HttpStatus.CONFLICT, code);
    }
}
