package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.google.cloud.storage.*;
import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.OwnedObject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Exact-generation legacy proof; neither URL appearance nor an untrusted claimant is proof. */
public final class LegacyAssetOwnership {
    private final JdbcTemplate jdbc;
    private final ObjectProvider<Storage> storage;
    private final OperationFence fence;
    private final AccountDeletionProperties properties;
    public LegacyAssetOwnership(JdbcTemplate jdbc,ObjectProvider<Storage> storage,OperationFence fence,AccountDeletionProperties properties){
        this.jdbc=jdbc;this.storage=storage;this.fence=fence;this.properties=properties;
    }
    public boolean approved(OwnedObject object){
        if(!safe(object))return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from account_deletion_legacy_objects
             where user_id=? and bucket=? and object_name=? and generation=?)
            """,Boolean.class,object.userId(),object.bucket(),object.name(),object.generation()));
    }
    public static boolean compatibleMetadata(long userId,Map<String,String> metadata){
        if(metadata==null)return true;
        return (!metadata.containsKey("nukleusUserId") || Long.toString(userId).equals(metadata.get("nukleusUserId"))) &&
            (!metadata.containsKey("nukleusExclusiveOwner") || "true".equals(metadata.get("nukleusExclusiveOwner")));
    }
    private boolean safe(OwnedObject object){
        return object.userId()>0 && object.generation()>0 && properties.getAllowedBuckets().contains(object.bucket()) &&
            object.name()!=null && object.name().startsWith("images/") && !object.name().contains("..") &&
            !object.name().contains("\\") && object.name().chars().noneMatch(c->c<32 || c==127);
    }
    /** Trusted reconciliation API, deliberately not exposed through REST. Evidence is an audited upload/provenance record digest. */
    public void verifyAndRecord(OwnedObject object,String evidenceHash){
        fence.requireHeld(true);
        if(!safe(object) || evidenceHash==null || !evidenceHash.matches("[0-9a-f]{64}"))throw AccountDeletionException.integration();
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from account_deletion_cleanup_plans where user_id=?)",
            Boolean.class,object.userId())))throw AccountDeletionException.integration();
        Blob blob=storage.getObject().get(BlobId.of(object.bucket(),object.name(),object.generation()));
        if(blob==null || !Objects.equals(blob.getGeneration(),object.generation()) ||
            !object.bucket().equals(blob.getBucket()) || !object.name().equals(blob.getName()) ||
            !compatibleMetadata(object.userId(),blob.getMetadata()))throw AccountDeletionException.integration();
        jdbc.update("""
            insert into account_deletion_legacy_objects(bucket,object_name,generation,user_id,evidence_hash)
            values(?,?,?,?,?)
            """,object.bucket(),object.name(),object.generation(),object.userId(),evidenceHash);
    }
    /** Recover an interrupted new-style upload using its existing reservation and exact cloud metadata. */
    public void reconcileReservedUpload(OwnedObject object){
        fence.requireHeld(true);
        if(object.userId()<=0 || object.generation()<=0 || !properties.getAllowedBuckets().contains(object.bucket()) ||
            object.name()==null || !object.name().startsWith("users/"+object.userId()+"/") ||
            object.name().contains("..") || object.name().contains("\\") ||
            Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from account_deletion_cleanup_plans where user_id=?)",
                Boolean.class,object.userId())))throw AccountDeletionException.integration();
        Blob blob=storage.getObject().get(BlobId.of(object.bucket(),object.name(),object.generation()));
        if(blob==null || !Objects.equals(blob.getGeneration(),object.generation()) ||
            !object.bucket().equals(blob.getBucket()) || !object.name().equals(blob.getName()) ||
            blob.getMetadata()==null || !Long.toString(object.userId()).equals(blob.getMetadata().get("nukleusUserId")) ||
            !"true".equals(blob.getMetadata().get("nukleusExclusiveOwner")))throw AccountDeletionException.integration();
        int updated=jdbc.update("""
            update account_deletion_owned_objects set generation=?
             where user_id=? and bucket=? and object_name=? and generation is null
            """,object.generation(),object.userId(),object.bucket(),object.name());
        if(updated!=1)throw AccountDeletionException.integration();
    }
}
