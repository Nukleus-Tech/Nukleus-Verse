package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Revocation is checked against PostgreSQL on every request; no cache, refresh or admin-JWT conversion. */
public final class JdbcSessionAuthority implements SessionAuthority {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AccountDeletionRepository requests;
    private final Phase3Properties properties;
    private final OperationFence fence;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private record Evidence(String digest) { @Override public String toString() { return "[redacted]"; } }
    public JdbcSessionAuthority(JdbcTemplate jdbc, UserRepository users, AccountDeletionRepository requests,
                                Phase3Properties properties, OperationFence fence, Clock clock) {
        this.jdbc = jdbc; this.users = users; this.requests = requests;
        this.properties = properties; this.fence = fence; this.clock = clock;
    }
    public boolean enabled() { return properties.isEnabled(); }
    /** Called only from the existing password-login endpoint; never takes a trusted client user ID. */
    public Map<String, Object> login(String email, String password) {
        fence.requireHeld(false);
        var user = users.findByEmailIgnoreCase(email);
        if (user == null || !PasswordCredentials.matches(password, user.getPassword())) {
            throw error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }
        if ("BLOCKED".equalsIgnoreCase(user.getAccountStatus())) throw error(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED");
        // Ambiguous legacy case-insensitive identities must be reconciled, never guessed.
        Long count = jdbc.queryForObject("select count(*) from users where lower(btrim(email))=lower(btrim(?))",
            Long.class, email);
        if (count == null || count != 1) throw AccountDeletionException.integration();
        if (PasswordCredentials.needsUpgrade(user.getPassword()) && !pending(user.getId())) {
            int upgraded = jdbc.update("update users set password=? where id=? and password=?",
                PasswordCredentials.encode(password), user.getId(), user.getPassword());
            if (upgraded != 1) throw error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }
        return issueGoogleSession(user);
    }
    /** Trusted server entry after password or signed Google subject + nonce verification, never an HTTP user-ID input. */
    Map<String, Object> issueGoogleSession(com.nukleus.vrmeeting.model.User user) {
        fence.requireHeld(false);
        if (!enabled() || user == null || "BLOCKED".equalsIgnoreCase(user.getAccountStatus()))
            throw error(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED");
        boolean pending = pending(user.getId());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = "nv1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        SessionScope scope = pending ? SessionScope.DELETION_ONLY : SessionScope.FULL;
        jdbc.update("""
            insert into account_deletion_sessions(token_hash,user_id,authenticated_at,expires_at,scope,request_epoch)
            values (?,?,?,?,?,?)
            """, digest(token), user.getId(), Timestamp.from(now),
            Timestamp.from(now.plus(properties.getSessionLifetime())), scope.name(),
            requests.findFirstByUserIdOrderByRequestedAtDescIdDesc(user.getId()).map(AccountDeletionRequest::getId).orElse(0L));
        // Retain existing successful-login bookkeeping for active accounts only.
        if (!pending) {
            user.setLastLogin(LocalDateTime.ofInstant(now, ZoneId.of("Asia/Kolkata")));
            // Do not merge a stale User snapshot over concurrent Admin Block/Unblock or avatar changes.
            jdbc.update("update users set last_login=? where id=?", Timestamp.valueOf(user.getLastLogin()), user.getId());
        }
        return Map.of("success", true, "message", "Login Successful", "userId", user.getId(),
            "name", Objects.toString(user.getName(), ""), "email", user.getEmail(),
            "accessToken", token, "tokenType", "Bearer", "expiresAt", now.plus(properties.getSessionLifetime()).toString(),
            "sessionScope", scope.name());
    }
    public Authentication authenticate(String header) {
        if (!enabled() || header == null || !header.matches("Bearer nv1_[A-Za-z0-9_-]{43}"))
            throw error(HttpStatus.UNAUTHORIZED, "USER_AUTHENTICATION_REQUIRED");
        String hash = digest(header.substring(7));
        var session = find(hash).filter(s -> !s.revoked() && s.expiresAt().isAfter(clock.instant()))
            .orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "SESSION_NOT_VERIFIED"));
        var user = users.findById(session.userId())
            .orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "SESSION_NOT_VERIFIED"));
        if ("BLOCKED".equalsIgnoreCase(user.getAccountStatus())) throw error(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED");
        var auth = UsernamePasswordAuthenticationToken.authenticated(
            new AuthenticatedAccountResolver.AccountPrincipal(session.userId(), session.authenticatedAt()),
            null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        auth.setDetails(new Evidence(hash)); // Neither raw token nor a client-controlled principal.
        return auth;
    }
    @Override public Optional<VerifiedSession> verifiedSession(Authentication auth) {
        if (!enabled() || auth == null || !(auth.getDetails() instanceof Evidence evidence)) return Optional.empty();
        return find(evidence.digest());
    }
    private Optional<VerifiedSession> find(String hash) {
        return jdbc.query("""
            select s.user_id,s.authenticated_at,s.expires_at,s.scope,
                (s.revoked_at is not null or exists(select 1 from account_deletion_requests r
                    where r.user_id=s.user_id and r.id>s.request_epoch))
            from account_deletion_sessions s where s.token_hash=?
            """, (rs, index) -> new VerifiedSession(rs.getLong(1), rs.getTimestamp(2).toInstant(),
                rs.getTimestamp(3).toInstant(), SessionScope.valueOf(rs.getString(4)),
                rs.getBoolean(5)), hash).stream().findFirst();
    }
    private boolean pending(long userId) {
        return requests.findFirstByUserIdOrderByRequestedAtDescIdDesc(userId)
            .map(r -> Set.of(AccountDeletionRequest.Status.PENDING, AccountDeletionRequest.Status.PROCESSING,
                AccountDeletionRequest.Status.FAILED).contains(r.getStatus())).orElse(false);
    }
    @Override public void revokeAll(long userId, long requestId) {
        fence.requireHeld(true);
        var request = requests.findById(requestId).orElseThrow(AccountDeletionException::integration);
        if (!Objects.equals(request.getUserId(), userId) || !pending(userId)) throw AccountDeletionException.integration();
        jdbc.update("""
            update account_deletion_sessions set revoked_at=coalesce(revoked_at,?),revoked_by_request=?
            where user_id=? and (scope='FULL' or request_epoch<?)
            """, Timestamp.from(clock.instant()), requestId, userId, requestId);
    }
    @Override public void requireDurableWriteFence(long userId, long requestId) {
        fence.requireHeld(true);
        var request = requests.findById(requestId).orElseThrow(AccountDeletionException::integration);
        if (!Objects.equals(request.getUserId(), userId) || request.getStatus() != AccountDeletionRequest.Status.PROCESSING ||
                request.getScheduledDeletionAt().isAfter(clock.instant())) throw AccountDeletionException.integration();
        Long live = jdbc.queryForObject("""
            select count(*) from account_deletion_sessions
            where user_id=? and scope='FULL' and revoked_at is null
            """, Long.class, userId);
        if (live == null || live != 0) throw AccountDeletionException.integration();
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static AccountDeletionException error(HttpStatus status, String code) {
        return new AccountDeletionException(status, code);
    }
}
