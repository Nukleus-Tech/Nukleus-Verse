package com.nukleus.vrmeeting.accountdeletion;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;

@Component
public class AuthenticatedAccountResolver {
    /**
     * Server-created principal supplied ONLY by the existing authentication layer after
     * verification. Never deserialize this type from HTTP or construct it from headers.
     * authenticatedAt is the last credential/Google reauthentication, NOT token refresh.
     */
    public record AccountPrincipal(long userId, Instant authenticatedAt) {}
    private final Clock clock;
    private final AccountDeletionProperties properties;
    public AuthenticatedAccountResolver(@Qualifier("accountDeletionClock") Clock clock,
                                        AccountDeletionProperties properties) {
        this.clock = clock; this.properties = properties;
    }
    public long resolve(Authentication authentication, boolean requireRecent) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccountDeletionException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        }
        if (!(authentication.getPrincipal() instanceof AccountPrincipal principal) ||
            principal.userId() <= 0 ||
            authentication.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))) {
            throw new AccountDeletionException(HttpStatus.FORBIDDEN, "USER_AUTHENTICATION_REQUIRED");
        }
        Instant now = clock.instant();
        if (requireRecent && (principal.authenticatedAt() == null ||
            principal.authenticatedAt().isAfter(now) ||
            principal.authenticatedAt().isBefore(now.minus(properties.getRecentAuthentication())))) {
            throw new AccountDeletionException(HttpStatus.UNAUTHORIZED, "RECENT_AUTHENTICATION_REQUIRED");
        }
        return principal.userId();
    }
}
