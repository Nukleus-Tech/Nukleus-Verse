package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.*;
import com.nukleus.vrmeeting.model.*;
import com.nukleus.vrmeeting.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;

/** Small hooks in existing controllers; legacy behavior is retained while integration is OFF. */
public final class AuthenticatedRouteGuard {
    private final Phase3Properties properties;
    private final PendingDeletionAccessAdapter access;
    private final UserRepository users;
    private final MeetingRepository meetings;
    private final JdbcTemplate jdbc;
    private final OperationFence fence;
    public AuthenticatedRouteGuard(Phase3Properties properties, PendingDeletionAccessAdapter access,
            UserRepository users, MeetingRepository meetings, JdbcTemplate jdbc, OperationFence fence) {
        this.properties = properties; this.access = access; this.users = users;
        this.meetings = meetings; this.jdbc = jdbc; this.fence = fence;
    }
    public boolean enabled() { return properties.isEnabled(); }
    public long currentUserId() {
        fence.requireHeld(false);
        return access.authorize(SecurityContextHolder.getContext().getAuthentication(),
            PendingDeletionAccessAdapter.Operation.NORMAL);
    }
    public void requireOwnEmail(String suppliedEmail) {
        if (!enabled()) return;
        User user = users.findById(currentUserId()).orElseThrow(AccountDeletionException::integration);
        if (suppliedEmail == null || user.getEmail() == null || !user.getEmail().equalsIgnoreCase(suppliedEmail.trim()))
            deny();
    }
    public void requireHost(String meetingId) {
        if (!enabled()) return;
        Meeting meeting = meetingId == null ? null : meetings.findByMeetingId(meetingId.trim());
        if (meeting == null) deny();
        requireOwnEmail(meeting.getHostEmail());
    }
    public void validateRegistration(User candidate) {
        if (!enabled()) return;
        // Registration is not an authenticated ownership/provisioning API.
        if (!references(candidate).isEmpty() || candidate.getGoogleId() != null ||
                candidate.getMeshyTaskId() != null || candidate.getCurrentMeetingId() != null) deny();
    }
    public void validateAvatar(User candidate) {
        if (!enabled()) return;
        requireOwnEmail(candidate.getEmail());
        long id = currentUserId();
        for (String url : references(candidate)) {
            var ref = GcsReference.parse(url);
            // URLs are not ownership proof. Only server-recorded personal uploads may be linked.
            if (ref.isPresent()) {
                Long count = jdbc.queryForObject("""
                    select count(*) from account_deletion_owned_objects
                    where bucket=? and object_name=? and user_id=? and generation is not null
                    """, Long.class, ref.get().bucket(), ref.get().objectName(), id);
                if (!Objects.equals(count, 1L) && !legacyOwned(ref.get().bucket(), ref.get().objectName(), id)) deny();
            }
        }
        // External Meshy URLs/IDs retain existing generation behavior; they BLOCK automatic cleanup
        // until archived and provider ownership/completion evidence has been reviewed.
    }
    public void validateMeetingFiles(Meeting candidate) {
        if (!enabled()) return;
        for (String url : new String[]{candidate.getRecordingUrl(), candidate.getPdfUrl(),
                candidate.getNotesUrl(), candidate.getPptUrl()}) {
            var ref = GcsReference.parse(url);
            if (ref.isPresent() && (ref.get().objectName().startsWith("users/") ||
                    Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from account_deletion_owned_objects where bucket=? and object_name=?)
                        """, Boolean.class, ref.get().bucket(), ref.get().objectName())) ||
                    legacyOwned(ref.get().bucket(), ref.get().objectName(), null))) deny();
        }
    }
    private boolean legacyOwned(String bucket, String name, Long userId) {
        if (jdbc.queryForObject("select to_regclass('account_deletion_legacy_objects')::text", String.class) == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from account_deletion_legacy_objects where bucket=? and object_name=?
                and (cast(? as bigint) is null or user_id=?))
            """, Boolean.class, bucket, name, userId, userId));
    }
    private static void deny() {
        throw new AccountDeletionException(HttpStatus.FORBIDDEN, "ACCOUNT_OWNERSHIP_REQUIRED");
    }
    private static java.util.List<String> references(User user) {
        return java.util.stream.Stream.of(user.getImageUrl(), user.getAvatarUrl(), user.getRiggedGlbUrl(),
            user.getWalkingGlbUrl(), user.getIdleGlbUrl(), user.getSittingGlbUrl())
            .filter(s -> s != null && !s.isBlank()).toList();
    }
}
