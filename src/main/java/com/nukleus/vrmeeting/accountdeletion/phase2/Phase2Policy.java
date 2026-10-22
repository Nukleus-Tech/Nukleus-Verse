package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.AccountDeletionException;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionProperties;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit composition only: no Spring stereotype, bean, filter or property binding.
 * Neither switch enables the Phase 1 API/worker flags.
 */
public record Phase2Policy(boolean adaptersEnabled, boolean cleanupEnabled) {
    public Phase2Policy() { this(false, false); }

    public void requireAdapters() {
        if (!adaptersEnabled) throw AccountDeletionException.integration();
    }
    public void requireCleanup(AccountDeletionProperties phase1) {
        requireAdapters();
        if (!cleanupEnabled || !phase1.isSchedulerEnabled() || !phase1.isDestructiveEnabled()) {
            throw AccountDeletionException.integration();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("External adapters require a transaction-free boundary");
        }
    }
}
