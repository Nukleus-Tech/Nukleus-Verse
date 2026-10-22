package com.nukleus.vrmeeting.accountdeletion;

import jakarta.validation.constraints.AssertTrue;
import java.time.Instant;

public final class AccountDeletionDtos {
    private AccountDeletionDtos() {}
    public record DeletionRequest(@AssertTrue Boolean confirm) {
        public boolean confirmed() { return Boolean.TRUE.equals(confirm); }
    }
    public record StatusResponse(String status, Instant requestedAt,
                                 Instant scheduledDeletionAt, Instant cancelledAt) {
        static StatusResponse from(AccountDeletionRequest r) {
            return r == null ? new StatusResponse("NONE", null, null, null) :
                new StatusResponse(r.getStatus().name(), r.getRequestedAt(),
                    r.getScheduledDeletionAt(), r.getCancelledAt());
        }
    }
    public record RequestResult(StatusResponse response, boolean created) {}
    public record ErrorResponse(String code, String message) {}
}
