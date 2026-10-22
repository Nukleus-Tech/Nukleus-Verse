package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.repository.UserRepository;
import com.nukleus.vrmeeting.service.GoogleAuthService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Verified Google subject only. No email-based account linking or client user IDs. */
public final class GoogleReauthentication {
    private final JdbcTemplate jdbc;
    private final GoogleAuthService google;
    private final JdbcSessionAuthority sessions;
    private final UserRepository users;
    private final OperationFence fence;
    private final GoogleGrantCleanup grants;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public GoogleReauthentication(JdbcTemplate jdbc,GoogleAuthService google,JdbcSessionAuthority sessions,
            UserRepository users,OperationFence fence,GoogleGrantCleanup grants,Clock clock){
        this.jdbc=jdbc;this.google=google;this.sessions=sessions;this.users=users;this.fence=fence;this.grants=grants;this.clock=clock;
    }
    public Map<String,Object> challenge(){
        fence.requireHeld(false);
        jdbc.update("delete from account_deletion_google_challenges where expires_at<?",Timestamp.from(clock.instant()));
        Long active=jdbc.queryForObject("select count(*) from account_deletion_google_challenges",Long.class);
        if(active==null || active>=10000) throw new AccountDeletionException(HttpStatus.TOO_MANY_REQUESTS,"GOOGLE_CHALLENGE_LIMIT");
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiry=clock.instant().plusSeconds(300);
        jdbc.update("insert into account_deletion_google_challenges(nonce_hash,created_at,expires_at) values(?,?,?)",
            JdbcSessionAuthority.digest(nonce),Timestamp.from(clock.instant()),Timestamp.from(expiry));
        return Map.of("nonce",nonce,"expiresAt",expiry.toString());
    }
    public Map<String,Object> login(String token){
        fence.requireHeld(false);
        GoogleIdToken.Payload payload;
        try{payload=google.verifyToken(token);}catch(Exception e){throw denied();}
        Object nonce=payload.get("nonce");
        if(!(nonce instanceof String value) || !value.matches("[A-Za-z0-9_-]{43}") ||
                payload.getIssuedAtTimeSeconds()==null ||
                Instant.ofEpochSecond(payload.getIssuedAtTimeSeconds()).isBefore(clock.instant().minusSeconds(300)) ||
                Instant.ofEpochSecond(payload.getIssuedAtTimeSeconds()).isAfter(clock.instant().plusSeconds(30))) throw denied();
        var user=users.findByGoogleId(payload.getSubject());
        if(user==null) throw new AccountDeletionException(HttpStatus.UNAUTHORIZED,"GOOGLE_ACCOUNT_NOT_LINKED");
        // Atomic one-time consumption also prevents the same token from reauthenticating after cancellation.
        int changed=jdbc.update("""
            update account_deletion_google_challenges set consumed_at=?
            where nonce_hash=? and consumed_at is null and expires_at>?
              and created_at<=?
            """,Timestamp.from(clock.instant()),JdbcSessionAuthority.digest(value),Timestamp.from(clock.instant()),
            Timestamp.from(Instant.ofEpochSecond(payload.getIssuedAtTimeSeconds()).plusSeconds(30)));
        if(changed!=1) throw denied();
        grants.recordVerifiedIdTokenLogin(user.getId(),payload.getSubject(),payload.getAudience().toString());
        return sessions.issueGoogleSession(user);
    }
    private static AccountDeletionException denied(){
        return new AccountDeletionException(HttpStatus.UNAUTHORIZED,"GOOGLE_REAUTHENTICATION_REQUIRED");
    }
}
