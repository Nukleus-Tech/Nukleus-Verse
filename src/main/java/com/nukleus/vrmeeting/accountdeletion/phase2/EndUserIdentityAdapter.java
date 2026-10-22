package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import java.time.Clock;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;

/** Does not authenticate a password/token or install a security filter. Explicit future integration only. */
public final class EndUserIdentityAdapter {
    private final Phase2Policy policy;
    private final SessionAuthority sessions;
    private final UserRepository users;
    private final AccountDeletionProperties properties;
    private final Clock clock;
    public EndUserIdentityAdapter(Phase2Policy policy, SessionAuthority sessions, UserRepository users,
                                  AccountDeletionProperties properties, Clock clock) {
        this.policy = policy; this.sessions = sessions; this.users = users;
        this.properties = properties; this.clock = clock;
    }
    public VerifiedSession identify(Authentication authentication, boolean recentRequired) {
        policy.requireAdapters();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccountDeletionException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        }
        // Current admin JWTs have String principals and ROLE_ADMIN: they are NEVER end-user evidence.
        if (!(authentication.getPrincipal() instanceof AuthenticatedAccountResolver.AccountPrincipal principal) ||
            principal.userId() <= 0 || authentication.getAuthorities().isEmpty() ||
            authentication.getAuthorities().stream().anyMatch(a -> !"ROLE_USER".equals(a.getAuthority()))) {
            throw new AccountDeletionException(HttpStatus.FORBIDDEN, "USER_AUTHENTICATION_REQUIRED");
        }
        VerifiedSession session = sessions.verifiedSession(authentication)
            .orElseThrow(() -> new AccountDeletionException(HttpStatus.UNAUTHORIZED, "SESSION_NOT_VERIFIED"));
        var now = clock.instant();
        if (session.revoked() || session.userId() != principal.userId() ||
            session.authenticatedAt() == null || !session.authenticatedAt().equals(principal.authenticatedAt()) ||
            session.authenticatedAt().isAfter(now) || session.expiresAt() == null ||
            !session.expiresAt().isAfter(now) || session.scope() == null) {
            throw new AccountDeletionException(HttpStatus.UNAUTHORIZED, "SESSION_NOT_VERIFIED");
        }
        if (recentRequired && session.authenticatedAt().isBefore(now.minus(properties.getRecentAuthentication()))) {
            throw new AccountDeletionException(HttpStatus.UNAUTHORIZED, "RECENT_AUTHENTICATION_REQUIRED");
        }
        var user = users.findById(session.userId()).orElseThrow(() ->
            new AccountDeletionException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND"));
        if (user.getId() == null || user.getId() != session.userId()) throw AccountDeletionException.integration();
        return session;
    }
}
