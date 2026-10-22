package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.UserSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;

/** Keep shared rows/files intact; remove only the deleting user's structured identity references. */
public final class RelatedAccountDataCleanup {
    private final CleanupIntegrationProperties properties;
    private final JdbcTemplate jdbc;
    private final OperationFence fence;
    public RelatedAccountDataCleanup(CleanupIntegrationProperties properties,JdbcTemplate jdbc,OperationFence fence){
        this.properties=properties;this.jdbc=jdbc;this.fence=fence;
    }
    public boolean enabled(){return properties.isAnonymizeMeetingReferences();}
    public void validate(UserSnapshot user){
        if(!enabled())throw new AccountDeletionException(HttpStatus.CONFLICT,"MEETING_REVIEW_REQUIRED");
        fence.requireHeld(true);
        // Never silently transfer/end another participant's live meeting.
        Long activeHosts=jdbc.queryForObject("""
            select count(*) from meetings where lower(btrim(host_email))=lower(btrim(?)) and status='ACTIVE'
            """,Long.class,user.email());
        if(activeHosts==null || activeHosts!=0)throw new AccountDeletionException(HttpStatus.CONFLICT,"MEETING_REVIEW_REQUIRED");
    }
    public void cleanup(UserSnapshot user){
        validate(user);
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw AccountDeletionException.integration();
        // No user ID/email survives in the host marker; meeting records and all shared assets remain.
        jdbc.update("""
            update meetings set host_email=? where lower(btrim(host_email))=lower(btrim(?))
            ""","deleted-"+UUID.randomUUID()+"@account.invalid",user.email());
        jdbc.update("""
            update meetings m set participant_emails=(
                select coalesce(string_agg(p.email,',' order by p.position),'')
                from unnest(string_to_array(coalesce(m.participant_emails,''),',')) with ordinality p(email,position)
                where lower(btrim(p.email))<>lower(btrim(?)))
            where exists(select 1 from unnest(string_to_array(coalesce(m.participant_emails,''),',')) p(email)
                where lower(btrim(p.email))=lower(btrim(?)))
            """,user.email(),user.email());
        // The deleting user's currentMeetingId disappears with the user. Other users are never updated.
    }
}
