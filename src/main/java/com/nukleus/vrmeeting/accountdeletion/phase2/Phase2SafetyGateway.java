package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.*;
import java.util.Objects;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;

/**
 * Reusable implementation of the existing gateway, composed by Phase3Configuration.
 * No independent component registration or duplicate lifecycle.
 */
public final class Phase2SafetyGateway implements AccountDeletionSafetyGateway {
    private final Phase2Policy policy;
    private final AccountDeletionProperties properties;
    private final ProtectionReadiness readiness;
    private final SessionRevocationAdapter revocation;
    private final CleanupJournal journal;
    private final PersonalDataInventoryAdapter personalData;
    private final StorageOwnershipAdapter storage;
    private final ExternalReferenceCleanupAdapter external;
    public Phase2SafetyGateway(Phase2Policy policy, AccountDeletionProperties properties,
            ProtectionReadiness readiness, SessionRevocationAdapter revocation, CleanupJournal journal,
            PersonalDataInventoryAdapter personalData, StorageOwnershipAdapter storage,
            ExternalReferenceCleanupAdapter external) {
        this.policy = policy; this.properties = properties; this.readiness = readiness;
        this.revocation = revocation; this.journal = journal; this.personalData = personalData;
        this.storage = storage; this.external = external;
    }
    @Override public void requireRequestProtection() {
        policy.requireAdapters();
        readiness.requireReady();
    }
    @Override public void revokeSessions(long userId) {
        requireRequestProtection();
        revocation.revokeForPendingRequest(userId);
    }
    @Override public CleanupInventory prepareCleanup(UserSnapshot user) {
        var plan = loadApproved(user);
        var names = storage.validateReferences(plan, user);
        personalData.requireCurrentAndUnshared(user, names);
        external.preflight(plan, user);
        storage.verifyAndJournalGenerations(plan);
        return new CleanupInventory(plan.objects());
    }
    @Override public void cleanupExternalIdentities(UserSnapshot user) {
        var plan = loadApproved(user);
        var names = storage.validateReferences(plan, user);
        personalData.requireCurrentAndUnshared(user, names);
        storage.requireAllApprovedGenerationsGone(plan);
        external.cleanup(plan, user);
        // No user deletion here: Phase 1 rechecks its fenced claim and deletes OTP/User atomically.
    }
    private ApprovedCleanupPlan loadApproved(UserSnapshot user) {
        policy.requireCleanup(properties);
        requireRequestProtection();
        Objects.requireNonNull(user);
        long requestId = revocation.requireProcessingFence(user.id());
        var plan = journal.approvedPlan(requestId, user.id());
        if (plan == null) throw AccountDeletionException.integration();
        plan.requireMatches(requestId, user);
        return plan;
    }
}
