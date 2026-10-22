package com.nukleus.vrmeeting.accountdeletion;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account")
public class AccountDeletionController {
    private final AccountDeletionService service;
    private final AuthenticatedAccountResolver identity;
    public AccountDeletionController(AccountDeletionService service, AuthenticatedAccountResolver identity) {
        this.service = service; this.identity = identity;
    }
    @PostMapping("/deletion-request")
    public ResponseEntity<AccountDeletionDtos.StatusResponse> request(Authentication authentication,
            @Valid @RequestBody AccountDeletionDtos.DeletionRequest body) {
        long userId = identity.resolve(authentication, true);
        if (!body.confirmed()) {
            throw new AccountDeletionException(HttpStatus.BAD_REQUEST, "CONFIRMATION_REQUIRED");
        }
        var result = service.request(userId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .cacheControl(CacheControl.noStore()).body(result.response());
    }
    @GetMapping("/deletion-status")
    public ResponseEntity<AccountDeletionDtos.StatusResponse> status(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.status(identity.resolve(authentication, false)));
    }
    @PostMapping("/cancel-deletion")
    public ResponseEntity<AccountDeletionDtos.StatusResponse> cancel(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.cancel(identity.resolve(authentication, true)));
    }
}
