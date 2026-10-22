package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Prepares the existing immutable Phase 2 plan, without performing any destructive I/O. */
public final class CleanupPreparation {
    private final CleanupIntegrationProperties properties;
    private final CleanupInventoryStore inventory;
    private final JdbcCleanupJournal journal;
    private final JdbcTemplate jdbc;
    private final OperationFence fence;
    private final MeshyTaskCleanup meshy;
    public CleanupPreparation(CleanupIntegrationProperties properties,CleanupInventoryStore inventory,
                              JdbcCleanupJournal journal,JdbcTemplate jdbc,OperationFence fence,MeshyTaskCleanup meshy){
        this.properties=properties;this.inventory=inventory;this.journal=journal;this.jdbc=jdbc;this.fence=fence;this.meshy=meshy;
    }
    public void prepare(AccountDeletionWorkStore.Claim claim){
        if(!properties.isAutomaticPreparationEnabled())return;
        fence.requireHeld(true);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from account_deletion_cleanup_plans where request_id=?)",
                Boolean.class,claim.requestId()))){
            inventory.requireReviewed(claim.userId(),claim.user());
            var plan=new ApprovedCleanupPlan(UUID.randomUUID(),claim.requestId(),claim.userId(),CleanupFingerprint.of(claim.user()),
                inventory.objects(claim.userId()),EnumSet.allOf(ApprovedCleanupPlan.ReviewDomain.class));
            journal.installProcessingPlan(plan,claim);
        }
        // Read-only provider verification supplies durable evidence for unarchived Meshy output URLs.
        if(claim.user().storageReferences().stream().anyMatch(url->GcsReference.parse(url).isEmpty()))
            meshy.verifyRemovable(claim.userId(),claim.user().meshyTaskId());
    }
    /** Trusted operator recovery after fixing evidence/configuration; does not reset deadline or attempt count. */
    public void retryReviewedFailure(long requestId, AccountDeletionProperties limits, java.time.Clock clock) {
        fence.requireHeld(true);
        if (!properties.isAutomaticPreparationEnabled() || !limits.isDestructiveEnabled() || !limits.isSchedulerEnabled())
            throw AccountDeletionException.integration();
        int updated = jdbc.update("""
            update account_deletion_requests set next_attempt_at=?,updated_at=?
            where id=? and status='FAILED' and retry_count<? and scheduled_deletion_at<=?
            """, java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()),
            requestId, limits.getMaxAttempts(), java.sql.Timestamp.from(clock.instant()));
        if (updated != 1) throw AccountDeletionException.integration();
        // Normal worker preflight revalidates all evidence. This method cannot bypass any cleanup check.
    }
}
