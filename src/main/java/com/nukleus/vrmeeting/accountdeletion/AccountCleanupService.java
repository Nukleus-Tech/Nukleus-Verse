package com.nukleus.vrmeeting.accountdeletion;

import com.google.cloud.storage.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.HashSet;

@Service
public class AccountCleanupService {
    private final AccountDeletionProperties properties;
    private final AccountDeletionSafetyGateway safety;
    private final ObjectProvider<Storage> storageProvider;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nukleus.vrmeeting.accountdeletion.phase3.LegacyAssetOwnership legacy;
    public AccountCleanupService(AccountDeletionProperties properties,
            AccountDeletionSafetyGateway safety, ObjectProvider<Storage> storageProvider) {
        this.properties = properties; this.safety = safety; this.storageProvider = storageProvider;
    }
    public void cleanup(AccountDeletionWorkStore.Claim claim) {
        if (!properties.isDestructiveEnabled() || !properties.isSchedulerEnabled()) {
            throw AccountDeletionException.integration();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("External cleanup must run outside a database transaction");
        }
        safety.requireRequestProtection();
        // Must attest complete ownership inventory, retention review and durable write/session fencing.
        var inventory = safety.prepareCleanup(claim.user());
        if (inventory == null) throw AccountDeletionException.integration();
        // Validate the ENTIRE plan before deleting any object.
        for (var object : inventory.objects()) validate(claim.userId(), object);
        if (!inventory.objects().isEmpty()) {
            Storage storage = storageProvider.getObject();
            for (var object : new HashSet<>(inventory.objects())) {
                BlobId id = BlobId.of(object.bucket(), object.name(), object.generation());
                Blob blob = storage.get(id);
                if (blob == null) continue; // Already deleted in a previous attempt.
                var metadata = blob.getMetadata();
                boolean owned = metadata != null && Long.toString(claim.userId()).equals(metadata.get("nukleusUserId")) &&
                    "true".equals(metadata.get("nukleusExclusiveOwner"));
                if (!owned && legacy != null && legacy.approved(object)) {
                    owned = com.nukleus.vrmeeting.accountdeletion.phase3.LegacyAssetOwnership.compatibleMetadata(claim.userId(), metadata);
                }
                if (!owned) {
                    throw AccountDeletionException.conflict("STORAGE_OWNERSHIP_UNVERIFIED");
                }
                // Never delete a replacement generation or use an arbitrary stored/signed URL.
                storage.delete(id, Storage.BlobSourceOption.generationMatch(object.generation()));
            }
        }
        // Provider-specific Google/Meshy removal requires reviewed permissions, implemented by gateway.
        safety.cleanupExternalIdentities(claim.user());
    }
    private void validate(long userId, AccountDeletionSafetyGateway.OwnedObject object) {
        if (object.userId() != userId || object.generation() <= 0 ||
            !properties.getAllowedBuckets().contains(object.bucket()) ||
            object.name() == null || (!object.name().startsWith("users/" + userId + "/") &&
                (legacy == null || !legacy.approved(object))) ||
            object.name().contains("..")) {
            throw AccountDeletionException.conflict("STORAGE_OWNERSHIP_UNVERIFIED");
        }
    }
}
