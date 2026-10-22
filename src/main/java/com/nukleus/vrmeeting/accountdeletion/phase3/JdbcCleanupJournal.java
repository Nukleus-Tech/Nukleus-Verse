package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Durable implementation of Phase 2's journal; no REST approval endpoint or automatic approval. */
public final class JdbcCleanupJournal implements CleanupJournal {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final UserRepository users;
    private final AccountDeletionRepository requests;
    private final TransactionTemplate transactions;
    private final OperationFence fence;
    private final CleanupInventoryStore inventory;
    public JdbcCleanupJournal(JdbcTemplate jdbc, ObjectMapper json, UserRepository users,
                              AccountDeletionRepository requests, PlatformTransactionManager manager, OperationFence fence) {
        this(jdbc, json, users, requests, manager, fence, null);
    }
    public JdbcCleanupJournal(JdbcTemplate jdbc, ObjectMapper json, UserRepository users,
                              AccountDeletionRepository requests, PlatformTransactionManager manager, OperationFence fence,
                              CleanupInventoryStore inventory) {
        this.jdbc = jdbc; this.json = json; this.users = users; this.requests = requests;
        this.transactions = new TransactionTemplate(manager); this.fence = fence; this.inventory = inventory;
    }
    /** Trusted offline integration ONLY, after human ownership/history/retention review. INSERT, never upsert. */
    public void installReviewedPlan(ApprovedCleanupPlan plan) {
        fence.requireHeld(true);
        var request = requests.findById(plan.requestId()).orElseThrow(AccountDeletionException::integration);
        if (!Objects.equals(request.getUserId(), plan.userId()) ||
                request.getStatus() != AccountDeletionRequest.Status.PENDING) throw AccountDeletionException.integration();
        plan.requireMatches(request.getId(), snapshot(plan.userId()));
        requireAllRegisteredObjects(plan);
        insertPlan(plan);
    }
    public void installProcessingPlan(ApprovedCleanupPlan plan, AccountDeletionWorkStore.Claim claim) {
        fence.requireHeld(true);
        if (plan.requestId() != claim.requestId() || plan.userId() != claim.userId() ||
            !Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from account_deletion_requests where id=? and user_id=?
                    and status='PROCESSING' and claim_token=?)
                """, Boolean.class, claim.requestId(), claim.userId(), claim.token()))) {
            throw AccountDeletionException.integration();
        }
        plan.requireMatches(claim.requestId(), snapshot(claim.userId()));
        requireAllRegisteredObjects(plan);
        insertPlan(plan);
    }
    private void insertPlan(ApprovedCleanupPlan plan) {
        try {
            String serialized = json.writeValueAsString(plan);
            transactions.executeWithoutResult(tx -> {
                jdbc.update("insert into account_deletion_cleanup_plans(id,request_id,user_id,plan) values (?,?,?,?::jsonb)",
                    plan.planId(), plan.requestId(), plan.userId(), serialized);
                if (inventory != null) inventory.bind(plan);
            });
        } catch (JsonProcessingException e) { throw AccountDeletionException.integration(); }
    }
    @Override public ApprovedCleanupPlan approvedPlan(long requestId, long userId) {
        var plans = jdbc.query("select plan::text from account_deletion_cleanup_plans where request_id=? and user_id=?",
            (row, i) -> parse(row.getString(1)), requestId, userId);
        if (plans.size() != 1) throw AccountDeletionException.integration();
        var plan = plans.getFirst();
        plan.requireMatches(requestId, snapshot(userId));
        requireAllRegisteredObjects(plan);
        if (inventory != null) inventory.requireBinding(plan);
        return plan;
    }
    private ApprovedCleanupPlan byId(UUID id) {
        var plans = jdbc.query("select plan::text from account_deletion_cleanup_plans where id=?",
            (row, i) -> parse(row.getString(1)), id);
        if (plans.size() != 1 || !id.equals(plans.getFirst().planId())) throw AccountDeletionException.integration();
        return plans.getFirst();
    }
    private ApprovedCleanupPlan parse(String value) {
        try { return json.readValue(value, ApprovedCleanupPlan.class); }
        catch (JsonProcessingException e) { throw AccountDeletionException.integration(); }
    }
    private UserSnapshot snapshot(long userId) {
        return PersonalDataInventoryAdapter.snapshot(users.findById(userId).orElseThrow(AccountDeletionException::integration));
    }
    private void requireAllRegisteredObjects(ApprovedCleanupPlan plan) {
        if (inventory != null && inventory.available()) {
            if (!new HashSet<>(plan.objects()).equals(new HashSet<>(inventory.objects(plan.userId())))) {
                throw AccountDeletionException.integration();
            }
            return;
        }
        List<OwnedObject> registered = jdbc.query("""
            select bucket,object_name,generation from account_deletion_owned_objects where user_id=?
            """, (rs, i) -> {
                long generation = rs.getLong(3);
                if (rs.wasNull() || generation <= 0) throw AccountDeletionException.integration();
                return new OwnedObject(plan.userId(), rs.getString(1), rs.getString(2), generation);
            }, plan.userId());
        // Includes superseded/unlinked photos and GLBs, not only the six current User URL columns.
        if (!new HashSet<>(plan.objects()).containsAll(registered)) throw AccountDeletionException.integration();
    }
    @Override public boolean generationWasVerified(UUID planId, OwnedObject object) {
        if (!byId(planId).objects().contains(object)) throw AccountDeletionException.integration();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from account_deletion_generation_receipts
            where plan_id=? and bucket=? and object_name=? and generation=?)
            """, Boolean.class, planId, object.bucket(), object.name(), object.generation()));
    }
    @Override public void recordVerifiedGenerations(UUID planId, Set<OwnedObject> objects) {
        fence.requireHeld(true);
        if (!new HashSet<>(byId(planId).objects()).equals(objects)) throw AccountDeletionException.integration();
        transactions.executeWithoutResult(tx -> {
            for (var object : objects) jdbc.update("""
                insert into account_deletion_generation_receipts(plan_id,bucket,object_name,generation)
                values (?,?,?,?) on conflict do nothing
                """, planId, object.bucket(), object.name(), object.generation());
        });
    }
    @Override public boolean externalCleanupCompleted(UUID planId, ExternalProvider provider) {
        var plan = byId(planId);
        var user = snapshot(plan.userId());
        plan.requireMatches(plan.requestId(), user);
        String reference = provider == ExternalProvider.MESHY_TASK ? user.meshyTaskId() : user.googleId();
        String hash = providerHash(plan, reference);
        if (hash == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from account_deletion_provider_receipts
            where plan_id=? and provider=? and reference_hash=? and completed_at is not null)
            """, Boolean.class, planId, provider.name(), hash));
    }
    @Override public void recordExternalCleanupCompleted(UUID planId, ExternalProvider provider) {
        fence.requireHeld(true);
        if (externalCleanupCompleted(planId, provider)) return;
        var plan = byId(planId);
        if (inventory == null || !inventory.available()) throw AccountDeletionException.integration();
        inventory.requireBinding(plan);
        boolean completed = provider == ExternalProvider.MESHY_TASK ?
            Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from account_deletion_provider_tasks where user_id=?)
                  and not exists(select 1 from account_deletion_provider_tasks where user_id=? and deleted_at is null)
                """, Boolean.class, plan.userId(), plan.userId())) :
            Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from account_deletion_google_links where user_id=? and revoked_at is not null)
                """, Boolean.class, plan.userId()));
        if (!completed) throw AccountDeletionException.integration();
        var user = snapshot(plan.userId());
        String reference = provider == ExternalProvider.MESHY_TASK ? user.meshyTaskId() : user.googleId();
        jdbc.update("""
            insert into account_deletion_provider_receipts(plan_id,provider,reference_hash,completed_at)
            values(?,?,?,now()) on conflict do nothing
            """, planId, provider.name(), providerHash(plan, reference));
    }
    private String providerHash(ApprovedCleanupPlan plan, String reference) {
        if (inventory != null && inventory.available()) {
            inventory.requireBinding(plan);
            return JdbcSessionAuthority.digest(Objects.toString(reference, "") + ":" + inventory.fingerprint(plan.userId()));
        }
        return reference == null || reference.isBlank() ? null : JdbcSessionAuthority.digest(reference);
    }
}

