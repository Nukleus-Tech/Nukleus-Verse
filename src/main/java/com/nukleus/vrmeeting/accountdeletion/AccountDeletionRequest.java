package com.nukleus.vrmeeting.accountdeletion;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_deletion_requests")
public class AccountDeletionRequest {
    public enum Status { PENDING, CANCELLED, PROCESSING, COMPLETED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id") private Long userId;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "scheduled_deletion_at", nullable = false) private Instant scheduledDeletionAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(name = "retry_count", nullable = false) private int retryCount;
    @Column(name = "last_error", length = 80) private String lastError;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Column(name = "claim_token") private UUID claimToken;

    protected AccountDeletionRequest() {}
    static AccountDeletionRequest pending(long userId, Instant now, Instant deadline) {
        AccountDeletionRequest r = new AccountDeletionRequest();
        r.userId = userId;
        r.requestedAt = r.createdAt = r.updatedAt = now;
        r.scheduledDeletionAt = deadline;
        r.status = Status.PENDING;
        return r;
    }
    boolean active() {
        return status == Status.PENDING || status == Status.PROCESSING || status == Status.FAILED;
    }
    boolean due(Instant now, int maxAttempts) {
        return userId != null && !scheduledDeletionAt.isAfter(now) &&
                (status == Status.PENDING || status == Status.PROCESSING ||
                 (status == Status.FAILED && retryCount < maxAttempts &&
                  nextAttemptAt != null && !nextAttemptAt.isAfter(now)));
    }
    void cancel(Instant now) {
        status = Status.CANCELLED; cancelledAt = updatedAt = now;
    }
    UUID claim(Instant now) {
        status = Status.PROCESSING; updatedAt = now; retryCount++;
        claimToken = UUID.randomUUID(); nextAttemptAt = null; lastError = null;
        return claimToken;
    }
    boolean owns(UUID token) {
        return status == Status.PROCESSING && token != null && token.equals(claimToken);
    }
    void fail(Instant now, String errorCode, Instant retryAt) {
        status = Status.FAILED; updatedAt = now; lastError = errorCode;
        nextAttemptAt = retryAt; claimToken = null;
    }
    void complete(Instant now) {
        status = Status.COMPLETED; completedAt = updatedAt = now;
        userId = null; claimToken = null; lastError = null; nextAttemptAt = null;
    }
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getScheduledDeletionAt() { return scheduledDeletionAt; }
    public Status getStatus() { return status; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public int getRetryCount() { return retryCount; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
}
