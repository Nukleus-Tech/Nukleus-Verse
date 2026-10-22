package com.nukleus.vrmeeting.accountdeletion;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Collections;

/** Google signature plus pinned audience AND service-account unique ID/email. No email-only trust. */
public final class AccountDeletionTriggerIdentity {
    private final AccountDeletionTriggerProperties properties;
    private final GoogleIdTokenVerifier verifier;
    private final Clock clock;
    public AccountDeletionTriggerIdentity(AccountDeletionTriggerProperties properties,
                                          GoogleIdTokenVerifier verifier, Clock clock) {
        this.properties = properties; this.verifier = verifier; this.clock = clock;
    }
    public void requireAuthorized(String authorization) {
        if (!properties.configured()) throw unavailable("DELETION_TRIGGER_NOT_CONFIGURED");
        if (authorization == null || authorization.length() > 16384 ||
                !authorization.startsWith("Bearer ")) throw denied();
        String raw = authorization.substring(7);
        if (!raw.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw denied();
        try {
            var token = verifier.verify(raw);
            if (token == null) throw denied();
            var payload = token.getPayload();
            long now = clock.instant().getEpochSecond();
            Long issued = payload.getIssuedAtTimeSeconds(), expires = payload.getExpirationTimeSeconds();
            if (!"https://accounts.google.com".equals(payload.getIssuer()) ||
                    !payload.getAudienceAsList().equals(Collections.singletonList(properties.getAudience())) ||
                    !properties.getServiceAccountSubject().equals(payload.getSubject()) ||
                    !properties.getServiceAccountEmail().equals(payload.getEmail()) ||
                    !Boolean.TRUE.equals(payload.getEmailVerified()) ||
                    issued == null || expires == null || expires <= now ||
                    issued > now + 30 || issued < now - 3630 || expires <= issued ||
                    expires - issued > 3630) throw denied();
            Object notBefore = payload.get("nbf");
            if (notBefore != null && (!(notBefore instanceof Number value) ||
                    value.longValue() > now + 30)) throw denied();
        } catch (IOException e) {
            // Temporary key-fetch outage should be retried; never log bearer tokens or exception text.
            throw unavailable("DELETION_TRIGGER_AUTH_UNAVAILABLE");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw denied();
        }
    }
    private static AccountDeletionException denied() {
        return new AccountDeletionException(HttpStatus.UNAUTHORIZED, "DELETION_TRIGGER_UNAUTHORIZED");
    }
    private static AccountDeletionException unavailable(String code) {
        return new AccountDeletionException(HttpStatus.SERVICE_UNAVAILABLE, code);
    }
}
