package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.controller.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Handles only the new guard exception; does not replace existing controller error behavior. */
@RestControllerAdvice(assignableTypes = {AuthController.class, AvatarController.class,
    MeetingController.class, StorageController.class, GoogleAccountController.class})
public class IntegrationExceptionHandler {
    @ExceptionHandler(AccountDeletionException.class)
    public ResponseEntity<AccountDeletionDtos.ErrorResponse> denied(AccountDeletionException error) {
        return ResponseEntity.status(error.getStatus()).cacheControl(CacheControl.noStore())
            .body(new AccountDeletionDtos.ErrorResponse(error.getCode(), error.getCode()));
    }
}

