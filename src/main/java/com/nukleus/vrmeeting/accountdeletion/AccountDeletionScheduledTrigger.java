package com.nukleus.vrmeeting.accountdeletion;

import com.nukleus.vrmeeting.accountdeletion.phase3.Phase3Properties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Synchronous Cloud Scheduler target: CPU remains assigned during this HTTP request. */
@RestController
public class AccountDeletionScheduledTrigger {
    public static final String PATH = "/internal/account-deletion/run";
    private static final Logger LOG = LoggerFactory.getLogger(AccountDeletionScheduledTrigger.class);
    private final AccountDeletionTriggerProperties trigger;
    private final AccountDeletionProperties deletion;
    private final Phase3Properties integration;
    private final AccountDeletionTriggerIdentity identity;
    private final AccountDeletionProcessor processor;
    public AccountDeletionScheduledTrigger(AccountDeletionTriggerProperties trigger,
            AccountDeletionProperties deletion, Phase3Properties integration,
            AccountDeletionTriggerIdentity identity, AccountDeletionProcessor processor) {
        this.trigger = trigger; this.deletion = deletion; this.integration = integration;
        this.identity = identity; this.processor = processor;
    }
    @PostMapping(PATH)
    public ResponseEntity<Void> run(HttpServletRequest request) throws java.io.IOException {
        if (!trigger.isEnabled() || !integration.isEnabled() ||
                !deletion.isSchedulerEnabled() || !deletion.isDestructiveEnabled()) {
            throw new AccountDeletionException(HttpStatus.SERVICE_UNAVAILABLE, "DELETION_WORKER_DISABLED");
        }
        if (request.getDispatcherType() != DispatcherType.REQUEST || request.isAsyncStarted() ||
                !request.getRequestURI().equals(request.getContextPath() + PATH) ||
                request.getQueryString() != null) throw invalid();
        var headers = request.getHeaders("Authorization");
        String authorization = headers != null && headers.hasMoreElements() ? headers.nextElement() : null;
        if (headers != null && headers.hasMoreElements()) throw invalid();
        identity.requireAuthorized(authorization);
        // No user ID, arbitrary request ID, deadline or "force" option, even in an ignored JSON body.
        if (request.getInputStream().read() != -1) throw invalid();
        processor.runScheduledBatch();
        // This acknowledges a cycle, NOT that every account was deleted. Durable request status is authoritative.
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @ExceptionHandler(AccountDeletionException.class)
    ResponseEntity<AccountDeletionDtos.ErrorResponse> known(AccountDeletionException e) {
        return error(e.getStatus(), e.getCode());
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<AccountDeletionDtos.ErrorResponse> unexpected(Exception e) {
        LOG.warn("Scheduled deletion cycle unavailable: {}", e.getClass().getSimpleName());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DELETION_WORKER_UNAVAILABLE");
    }
    private static AccountDeletionException invalid() {
        return new AccountDeletionException(HttpStatus.BAD_REQUEST, "INVALID_DELETION_TRIGGER");
    }
    private static ResponseEntity<AccountDeletionDtos.ErrorResponse> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(new AccountDeletionDtos.ErrorResponse(code, code));
    }
}
