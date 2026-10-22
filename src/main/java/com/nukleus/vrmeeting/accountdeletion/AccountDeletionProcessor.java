package com.nukleus.vrmeeting.accountdeletion;

import com.google.cloud.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.sql.SQLException;
import java.time.Clock;

@Service
public class AccountDeletionProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(AccountDeletionProcessor.class);
    private final AccountDeletionProperties properties;
    private final AccountDeletionRepository repository;
    private final AccountDeletionWorkStore work;
    private final AccountDeletionWorkerLock locks;
    private final AccountCleanupService cleanup;
    private final AccountDeletionSafetyGateway safety;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nukleus.vrmeeting.accountdeletion.phase3.OperationFence integrationFence;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nukleus.vrmeeting.accountdeletion.phase3.CleanupPreparation preparation;
    public AccountDeletionProcessor(AccountDeletionProperties properties, AccountDeletionRepository repository,
            AccountDeletionWorkStore work, AccountDeletionWorkerLock locks, AccountCleanupService cleanup,
            AccountDeletionSafetyGateway safety, @Qualifier("accountDeletionClock") Clock clock) {
        this.properties = properties; this.repository = repository; this.work = work; this.locks = locks;
        this.cleanup = cleanup; this.safety = safety; this.clock = clock;
    }
    public void runBatch() {
        if (!properties.isSchedulerEnabled() || !properties.isDestructiveEnabled()) return;
        try {
            safety.requireRequestProtection();
            for (long id : repository.findDueIds(clock.instant(), properties.getMaxAttempts(),
                    PageRequest.of(0, properties.getBatchSize()))) process(id);
        } catch (Exception e) {
            LOG.warn("Account deletion batch unavailable: {}", e.getClass().getSimpleName());
        }
    }
    void process(long id) {
        AccountDeletionWorkStore.Claim claim = null;
        try {
            var acquired = locks.tryAcquire(id);
            if (acquired.isEmpty()) return;
            try (var lock = acquired.get();
                 var ingress = integrationFence == null ? null : integrationFence.acquire(true)) {
                var claimed = work.claim(id);
                if (claimed.isEmpty()) return;
                claim = claimed.get();
                lock.assertLive();
                work.validateCleanup(claim);
                if (preparation != null) preparation.prepare(claim);
                if (ingress != null) ingress.assertLive();
                cleanup.cleanup(claim);
                lock.assertLive();
                if (ingress != null) ingress.assertLive();
                work.complete(claim);
            }
        } catch (Exception e) {
            if (claim != null) {
                boolean retryable = e instanceof SQLException || e instanceof TransientDataAccessException ||
                    (e instanceof StorageException storage && (storage.getCode() == 408 ||
                        storage.getCode() == 429 || storage.getCode() >= 500));
                String code = e instanceof StorageException ? "STORAGE_FAILURE" : "CLEANUP_FAILURE";
                if (e instanceof AccountDeletionException deletion) {
                    code = switch (deletion.getCode()) {
                        case "STORAGE_OWNERSHIP_UNVERIFIED", "MEETING_REVIEW_REQUIRED",
                             "CLAIM_LOST", "ACCOUNT_CHANGED", "INTEGRATION_REQUIRED" -> deletion.getCode();
                        default -> "CLEANUP_FAILURE";
                    };
                }
                try { work.fail(claim, code, retryable); }
                catch (Exception failure) {
                    LOG.warn("Account deletion failure recording unavailable: {}",
                        failure.getClass().getSimpleName());
                }
            }
            LOG.warn("Account deletion attempt failed: {}", e.getClass().getSimpleName());
        }
    }
}
