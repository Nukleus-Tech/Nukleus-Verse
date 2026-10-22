package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.OwnedObject;
import org.springframework.security.core.Authentication;
import java.time.Instant;
import java.util.*;

/**
 * Required TRUSTED SERVER integrations, never HTTP request DTOs.
 * No production implementations are registered; no in-memory production substitute is supplied.
 */
public final class Phase2Ports {
    private Phase2Ports() {}
    /** Explicit translation of a recoverable provider/journal outage into Phase 1's existing
     * bounded transient-data retry classification. Never use for ownership/permission failures. */
    public static final class RetryableIntegrationFailure
            extends org.springframework.dao.TransientDataAccessResourceException {
        public RetryableIntegrationFailure() {
            super("Account deletion integration temporarily unavailable");
        }
    }
    public enum SessionScope { FULL, DELETION_ONLY }
    public record VerifiedSession(long userId, Instant authenticatedAt, Instant expiresAt,
                                  SessionScope scope, boolean revoked) {}

    public interface SessionAuthority {
        /** Resolve only an already verified end-user session; validate revocation on EVERY call.
         * Refresh time is not credential authentication time. Missing/unavailable evidence denies access. */
        Optional<VerifiedSession> verifiedSession(Authentication authentication);
        /** Durably revoke ordinary access and refresh sessions, idempotently keyed by deletion request.
         * Never revoke a different user or convert a revoked token into a cancellation credential. */
        void revokeAll(long userId, long deletionRequestId);
        /** Prove all writes/jobs/sessions are fenced across instances for this request.
         * A DB-read error, incomplete revocation, or in-flight writer must throw, not return success. */
        void requireDurableWriteFence(long userId, long deletionRequestId);
    }
    public interface ProtectionReadiness {
        /** Verify real end-user auth, all ingress restrictions, durable revocation/reconciliation,
         * secure cancellation reauthentication, and reviewed storage/retention/provider integrations.
         * This must NOT be implemented as a configuration-only "true" or no-op. */
        void requireReady();
    }
    public interface CleanupJournal {
        /** Trusted reviewed inventory, immutable per request. Missing approval must throw.
         * Must cover unlinked historical objects, aliases, logs/backups and retention, not just six URLs.
         * Must never accept the manifest from the client or replace it during a retry. */
        ApprovedCleanupPlan approvedPlan(long deletionRequestId, long userId);
        /** True only for previously persisted proof of this exact approved object generation. */
        boolean generationWasVerified(UUID planId, OwnedObject object);
        /** Durably and atomically persist all verified generations BEFORE any delete is allowed.
         * Bind to the immutable plan; never bless an object absent from that plan. */
        void recordVerifiedGenerations(UUID planId, Set<OwnedObject> objects);
        boolean externalCleanupCompleted(UUID planId, ExternalProvider provider);
        void recordExternalCleanupCompleted(UUID planId, ExternalProvider provider);
    }
    public enum ExternalProvider { GOOGLE_APPLICATION_GRANT, MESHY_TASK }
    public interface ExternalReferenceClient {
        /** Includes superseded provider tasks even when User.meshyTaskId is now empty. */
        default boolean hasAdditionalReferences(long userId) { return false; }
        /** Must prove ownership and permitted application-specific removal without deleting anything.
         * A Google subject alone does NOT establish an OAuth grant or its ownership. */
        void verifyRemovable(long userId, String providerReference);
        /** Idempotent across retries/processes using planId; revoke only this application's reference.
         * Never delete the person's Google account. No unsupported API endpoints may be invented.
         * Map verified recoverable outages to RetryableIntegrationFailure; all unknown or permanent
         * failures remain fail-closed/manual-review errors under the unchanged Phase 1 processor. */
        void removeApplicationReference(long userId, String providerReference, UUID planId);
    }
}
