package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.*;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Durable reviewed coverage and immutable inventory bindings, reused by automatic plan preparation. */
public final class CleanupInventoryStore {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final OperationFence fence;
    private final ObjectMapper json;
    public CleanupInventoryStore(JdbcTemplate jdbc,UserRepository users,OperationFence fence,ObjectMapper json){
        this.jdbc=jdbc;this.users=users;this.fence=fence;this.json=json;
    }
    public boolean available(){
        return jdbc.queryForObject("select to_regclass('account_deletion_coverage')::text",String.class)!=null;
    }
    public List<OwnedObject> objects(long userId){
        return jdbc.query("""
            select bucket,object_name,generation from account_deletion_owned_objects where user_id=?
            union all select bucket,object_name,generation from account_deletion_legacy_objects where user_id=?
            order by bucket,object_name,generation
            """,(rs,index)->{
                long generation=rs.getLong(3);
                if(rs.wasNull() || generation<=0)throw AccountDeletionException.integration();
                return new OwnedObject(userId,rs.getString(1),rs.getString(2),generation);
            },userId,userId);
    }
    public String fingerprint(long userId){
        try{
            var tasks=jdbc.queryForList("select task_id::text,kind,evidence_hash from account_deletion_provider_tasks where user_id=? order by task_id",userId);
            var grants=jdbc.queryForList("select grant_id::text,subject_hash,client_id,mode from account_deletion_google_links where user_id=?",userId);
            var legacy=jdbc.queryForList("select bucket,object_name,generation,evidence_hash from account_deletion_legacy_objects where user_id=? order by bucket,object_name,generation",userId);
            return JdbcSessionAuthority.digest(json.writeValueAsString(List.of(objects(userId),tasks,grants,legacy)));
        }catch(java.io.IOException e){throw AccountDeletionException.integration();}
    }
    public void bind(ApprovedCleanupPlan plan){
        if(!available())return;
        jdbc.update("insert into account_deletion_plan_bindings(plan_id,inventory_fingerprint) values(?,?)",
            plan.planId(),fingerprint(plan.userId()));
    }
    public void requireBinding(ApprovedCleanupPlan plan){
        if(!available())return;
        var rows=jdbc.queryForList("select inventory_fingerprint from account_deletion_plan_bindings where plan_id=?",String.class,plan.planId());
        if(rows.size()!=1 || !rows.getFirst().equals(fingerprint(plan.userId())))throw AccountDeletionException.integration();
    }
    public void requireReviewed(long userId,UserSnapshot snapshot){
        var rows=jdbc.queryForList("select snapshot_fingerprint,inventory_fingerprint from account_deletion_coverage where user_id=?",userId);
        if(rows.size()!=1 || !CleanupFingerprint.of(snapshot).equals(rows.getFirst().get("snapshot_fingerprint")) ||
            !fingerprint(userId).equals(rows.getFirst().get("inventory_fingerprint")))throw AccountDeletionException.integration();
    }
    /** Evidence import after human review of all domains, including unlinked assets, backups and retention. Not a user/admin REST API. */
    public void reviewCoverage(long userId,String evidenceHash,Set<ApprovedCleanupPlan.ReviewDomain> domains){
        fence.requireHeld(true);
        if(evidenceHash==null || !evidenceHash.matches("[0-9a-f]{64}") ||
            !domains.containsAll(EnumSet.allOf(ApprovedCleanupPlan.ReviewDomain.class)))throw AccountDeletionException.integration();
        var snapshot=PersonalDataInventoryAdapter.snapshot(users.findById(userId).orElseThrow(AccountDeletionException::integration));
        jdbc.update("""
            insert into account_deletion_coverage(user_id,snapshot_fingerprint,inventory_fingerprint,evidence_hash) values(?,?,?,?)
            on conflict(user_id) do update set snapshot_fingerprint=excluded.snapshot_fingerprint,
                inventory_fingerprint=excluded.inventory_fingerprint,evidence_hash=excluded.evidence_hash,reviewed_at=now()
            """,userId,CleanupFingerprint.of(snapshot),fingerprint(userId),evidenceHash);
    }
    public void recordVerifiedMeshyTask(long userId,String kind,UUID taskId,String evidenceHash){
        fence.requireHeld(true);
        if(evidenceHash==null || !evidenceHash.matches("[0-9a-f]{64}") ||
            !Set.of("image-to-image","image-to-3d","rigging","animations").contains(kind) ||
            Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from account_deletion_cleanup_plans where user_id=?)",
                Boolean.class,userId)))throw AccountDeletionException.integration();
        jdbc.update("insert into account_deletion_provider_tasks(task_id,user_id,kind,evidence_hash) values(?,?,?,?)",
            taskId,userId,kind,evidenceHash);
    }
}
