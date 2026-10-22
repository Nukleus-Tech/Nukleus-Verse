package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.ExternalReferenceClient;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Removes only this application's grant/link, NEVER a Google account. */
public final class GoogleGrantCleanup implements ExternalReferenceClient {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final CleanupIntegrationProperties properties;
    private final OperationFence fence;
    private final ProviderHttp http;
    private final ProviderTokenCipher cipher;
    private final ObjectMapper json;
    public GoogleGrantCleanup(JdbcTemplate jdbc,UserRepository users,CleanupIntegrationProperties properties,
            OperationFence fence,ProviderHttp http,ProviderTokenCipher cipher,ObjectMapper json){
        this.jdbc=jdbc;this.users=users;this.properties=properties;this.fence=fence;this.http=http;this.cipher=cipher;this.json=json;
    }
    public void recordVerifiedIdTokenLogin(long userId,String subject,String clientId){
        fence.requireHeld(false);requireUser(userId,subject);
        if(!properties.getGoogleClientIds().contains(clientId))throw AccountDeletionException.integration();
        jdbc.update("""
            insert into account_deletion_google_links(user_id,grant_id,subject_hash,client_id,mode)
            values(?,?,?,?,'ID_TOKEN_ONLY') on conflict(user_id) do nothing
            """,userId,UUID.randomUUID(),JdbcSessionAuthority.digest(subject),clientId);
        row(userId,subject);
    }
    /** Trusted server-side OAuth exchange/reconciliation input, not an end-user HTTP grant-import endpoint. */
    public void recordVerifiedGrant(long userId,String subject,String clientId,String refreshToken){
        fence.requireHeld(true);requireUser(userId,subject);
        if(!properties.getGoogleClientIds().contains(clientId) ||
            Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from account_deletion_cleanup_plans where user_id=?)",
                Boolean.class,userId)))throw AccountDeletionException.integration();
        byte[] encrypted=cipher.encrypt(userId,subject,refreshToken);
        jdbc.update("""
            insert into account_deletion_google_links(user_id,grant_id,subject_hash,client_id,mode,encrypted_token)
            values(?,?,?,?,'OAUTH_GRANT',?)
            on conflict(user_id) do update set grant_id=excluded.grant_id,subject_hash=excluded.subject_hash,
              client_id=excluded.client_id,mode=excluded.mode,encrypted_token=excluded.encrypted_token,revoked_at=null
            """,userId,UUID.randomUUID(),JdbcSessionAuthority.digest(subject),clientId,encrypted);
    }
    private void requireUser(long id,String subject){
        var user=users.findById(id).orElseThrow(AccountDeletionException::integration);
        if(subject==null || !subject.equals(user.getGoogleId()))throw AccountDeletionException.integration();
    }
    private Map<String,Object> row(long userId,String subject){
        requireUser(userId,subject);
        var rows=jdbc.queryForList("select * from account_deletion_google_links where user_id=? and subject_hash=?",
            userId,JdbcSessionAuthority.digest(subject));
        if(rows.size()!=1 || !properties.getGoogleClientIds().contains(rows.getFirst().get("client_id")))
            throw AccountDeletionException.integration();
        return rows.getFirst();
    }
    @Override public void verifyRemovable(long userId,String subject){
        fence.requireHeld(true);
        var link=row(userId,subject);
        if("OAUTH_GRANT".equals(link.get("mode")) && link.get("revoked_at")==null){
            if(!properties.isProviderCallsEnabled())throw AccountDeletionException.integration();
            cipher.decrypt(userId,subject,(byte[])link.get("encrypted_token"));
        }
    }
    @Override public void removeApplicationReference(long userId,String subject,UUID planId){
        verifyRemovable(userId,subject);
        var link=row(userId,subject);
        if("OAUTH_GRANT".equals(link.get("mode")) && link.get("revoked_at")==null){
            String token=cipher.decrypt(userId,subject,(byte[])link.get("encrypted_token"));
            var result=http.exchange("POST",URI.create("https://oauth2.googleapis.com/revoke"),
                Map.of("Content-Type","application/x-www-form-urlencoded"),
                "token="+URLEncoder.encode(token,StandardCharsets.UTF_8));
            boolean alreadyRevoked=false;
            if(result.status()==400)try{alreadyRevoked="invalid_token".equals(json.readTree(result.body()).path("error").asText());}
                catch(Exception ignored){/* Unknown error is not success. */}
            if(!alreadyRevoked)ProviderHttp.requireSuccess(result.status());
        }
        jdbc.update("update account_deletion_google_links set revoked_at=coalesce(revoked_at,now()),encrypted_token=null where user_id=?",userId);
    }
}
