package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.*;
import com.nukleus.vrmeeting.model.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;

final class Phase2TestSupport {
    static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Phase2Policy ENABLED = new Phase2Policy(true, true);
    static final String BUCKET = "synthetic-phase2";
    static final String URL = "https://storage.googleapis.com/" + BUCKET + "/users/42/photo.png";
    static AccountDeletionProperties properties() {
        var p = new AccountDeletionProperties(); p.setApiEnabled(true);
        p.setSchedulerEnabled(true); p.setDestructiveEnabled(true); p.setAllowedBuckets(Set.of(BUCKET));
        return p;
    }
    static User user(long id) {
        User u = new User(); ReflectionTestUtils.setField(u, "id", id);
        u.setEmail("synthetic" + id + "@example.invalid"); return u;
    }
    static UsernamePasswordAuthenticationToken authentication(long id, Instant at) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedAccountResolver.AccountPrincipal(id, at),
            null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
    static VerifiedSession session(long id, Instant at, SessionScope scope) {
        return new VerifiedSession(id, at, NOW.plusSeconds(300), scope, false);
    }
    static UserSnapshot snapshot() {
        return new UserSnapshot(42, "synthetic42@example.invalid", List.of(URL), null, null, null);
    }
    static OwnedObject object() { return new OwnedObject(42, BUCKET, "users/42/photo.png", 7); }
    static ApprovedCleanupPlan plan(UserSnapshot user, List<OwnedObject> objects) {
        return new ApprovedCleanupPlan(UUID.randomUUID(), 11, user.id(), CleanupFingerprint.of(user),
            objects, EnumSet.allOf(ApprovedCleanupPlan.ReviewDomain.class));
    }
    static AccountDeletionRequest request(AccountDeletionRequest.Status status) {
        var r = org.springframework.beans.BeanUtils.instantiateClass(AccountDeletionRequest.class);
        ReflectionTestUtils.setField(r, "id", 11L); ReflectionTestUtils.setField(r, "userId", 42L);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "requestedAt", NOW.minus(Duration.ofDays(8)));
        ReflectionTestUtils.setField(r, "scheduledDeletionAt", NOW.minus(Duration.ofDays(1)));
        return r;
    }
}
