package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.google.cloud.storage.*;
import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import java.util.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;

/**
 * Validates the complete plan before the unchanged Phase 1 storage cleanup runs.
 * Never infers ownership from a URL or lists/deletes a bucket. Does not perform object deletes.
 */
public final class StorageOwnershipAdapter {
    private final Phase2Policy policy;
    private final AccountDeletionProperties properties;
    private final ObjectProvider<Storage> storage;
    private final CleanupJournal journal;
    private final com.nukleus.vrmeeting.accountdeletion.phase3.LegacyAssetOwnership legacy;
    private final com.nukleus.vrmeeting.accountdeletion.phase3.VerifiedProviderAssets providerAssets;
    public StorageOwnershipAdapter(Phase2Policy policy, AccountDeletionProperties properties,
                                   ObjectProvider<Storage> storage, CleanupJournal journal) {
        this(policy, properties, storage, journal, null);
    }
    public StorageOwnershipAdapter(Phase2Policy policy, AccountDeletionProperties properties,
            ObjectProvider<Storage> storage, CleanupJournal journal,
            com.nukleus.vrmeeting.accountdeletion.phase3.LegacyAssetOwnership legacy) {
        this(policy, properties, storage, journal, legacy, null);
    }
    public StorageOwnershipAdapter(Phase2Policy policy, AccountDeletionProperties properties,
            ObjectProvider<Storage> storage, CleanupJournal journal,
            com.nukleus.vrmeeting.accountdeletion.phase3.LegacyAssetOwnership legacy,
            com.nukleus.vrmeeting.accountdeletion.phase3.VerifiedProviderAssets providerAssets) {
        this.policy = policy; this.properties = properties; this.storage = storage; this.journal = journal; this.legacy = legacy;
        this.providerAssets = providerAssets;
    }
    public Set<GcsReference> validateReferences(ApprovedCleanupPlan plan, UserSnapshot user) {
        policy.requireCleanup(properties);
        plan.requireMatches(plan.requestId(), user);
        Set<GcsReference> inventory = new HashSet<>();
        for (OwnedObject object : plan.objects()) {
            if (object.userId() != user.id() || object.generation() <= 0 ||
                !properties.getAllowedBuckets().contains(object.bucket()) ||
                object.name() == null || (!object.name().startsWith("users/" + user.id() + "/") &&
                    (legacy == null || !legacy.approved(object))) ||
                object.name().contains("..") || object.name().contains("\\") ||
                object.name().chars().anyMatch(c -> c < 32 || c == 127)) fail();
            inventory.add(new GcsReference(object.bucket(), object.name()));
        }
        for (String url : user.storageReferences()) {
            if (GcsReference.parse(url).isEmpty() && providerAssets != null && providerAssets.approved(user.id(), url)) continue;
            GcsReference reference = GcsReference.parse(url).orElseThrow(this::ownershipFailure);
            if (!inventory.contains(reference)) fail();
        }
        // An empty manifest is valid ONLY when every review domain is approved and no references remain.
        return Set.copyOf(inventory);
    }
    public void verifyAndJournalGenerations(ApprovedCleanupPlan plan) {
        policy.requireCleanup(properties);
        if (plan.objects().isEmpty()) return;
        Storage client = storage.getObject();
        Set<OwnedObject> verified = new HashSet<>();
        for (OwnedObject object : plan.objects()) {
            Blob blob = client.get(BlobId.of(object.bucket(), object.name(), object.generation()));
            if (blob == null) {
                if (!journal.generationWasVerified(plan.planId(), object)) fail();
            } else {
                Map<String, String> metadata = blob.getMetadata();
                boolean owned = metadata != null && Long.toString(object.userId()).equals(metadata.get("nukleusUserId")) &&
                    "true".equals(metadata.get("nukleusExclusiveOwner"));
                if (!owned && legacy != null && legacy.approved(object)) {
                    owned = com.nukleus.vrmeeting.accountdeletion.phase3.LegacyAssetOwnership.compatibleMetadata(object.userId(), metadata);
                }
                if (!Objects.equals(blob.getGeneration(), object.generation()) ||
                    !Objects.equals(blob.getBucket(), object.bucket()) ||
                    !Objects.equals(blob.getName(), object.name()) || !owned) fail();
            }
            verified.add(object);
        }
        // Failure to persist evidence stops before Phase 1's first destructive storage operation.
        journal.recordVerifiedGenerations(plan.planId(), Set.copyOf(verified));
    }
    public void requireAllApprovedGenerationsGone(ApprovedCleanupPlan plan) {
        policy.requireCleanup(properties);
        if (plan.objects().isEmpty()) return;
        Storage client = storage.getObject();
        for (OwnedObject object : plan.objects()) {
            if (!journal.generationWasVerified(plan.planId(), object)) fail();
            if (client.get(BlobId.of(object.bucket(), object.name(), object.generation())) != null) {
                throw new AccountDeletionException(HttpStatus.CONFLICT, "STORAGE_CLEANUP_INCOMPLETE");
            }
        }
    }
    private AccountDeletionException ownershipFailure() {
        return new AccountDeletionException(HttpStatus.CONFLICT, "STORAGE_OWNERSHIP_UNVERIFIED");
    }
    private void fail() { throw ownershipFailure(); }
}
