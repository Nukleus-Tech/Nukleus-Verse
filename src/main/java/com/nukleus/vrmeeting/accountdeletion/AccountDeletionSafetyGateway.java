package com.nukleus.vrmeeting.accountdeletion;

import com.nukleus.vrmeeting.model.User;
import java.util.List;
import java.util.stream.Stream;

/**
 * Integration seam, NOT a second authentication system. The default bean denies everything.
 * A reviewed implementation must provide durable session revocation, enforce pending-account
 * restrictions on ALL ingress paths, inventory ALL user data (not only six URLs), check retention
 * and shared references, and keep writes blocked until cancellation or completion.
 * Methods performing external work must be idempotent and must not log personal data.
 */
public interface AccountDeletionSafetyGateway {
    void requireRequestProtection();
    void revokeSessions(long userId);
    CleanupInventory prepareCleanup(UserSnapshot user);
    void cleanupExternalIdentities(UserSnapshot user);

    record OwnedObject(long userId, String bucket, String name, long generation) {}
    record CleanupInventory(List<OwnedObject> objects) {
        public CleanupInventory { objects = List.copyOf(objects); }
    }
    record UserSnapshot(long id, String email, List<String> storageReferences,
                        String meshyTaskId, String googleId, String currentMeetingId) {
        public UserSnapshot { storageReferences = List.copyOf(storageReferences); }
        static UserSnapshot from(User u) {
            return new UserSnapshot(u.getId(), u.getEmail(),
                Stream.of(u.getImageUrl(), u.getAvatarUrl(), u.getRiggedGlbUrl(),
                    u.getWalkingGlbUrl(), u.getIdleGlbUrl(), u.getSittingGlbUrl())
                    .filter(s -> s != null && !s.isBlank()).distinct().toList(),
                u.getMeshyTaskId(), u.getGoogleId(), u.getCurrentMeetingId());
        }
    }
}
