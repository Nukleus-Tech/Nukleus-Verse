package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.UserSnapshot;
import com.nukleus.vrmeeting.model.User;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Read-only DB adapter. Final OTP/user deletion remains in Phase 1 AccountDeletionWorkStore.
 * No accountStatus writes, meeting mutations, admin mutations, DDL or cascade shortcuts.
 * Optional reviewed related-data policy is composed by Phase 3; mutations remain in the final worker transaction.
 */
public final class PersonalDataInventoryAdapter {
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final com.nukleus.vrmeeting.accountdeletion.phase3.RelatedAccountDataCleanup related;
    public PersonalDataInventoryAdapter(UserRepository users, JdbcTemplate jdbc) {
        this(users, jdbc, null);
    }
    public PersonalDataInventoryAdapter(UserRepository users, JdbcTemplate jdbc,
            com.nukleus.vrmeeting.accountdeletion.phase3.RelatedAccountDataCleanup related) {
        this.users = users; this.jdbc = jdbc; this.related = related;
    }
    public void requireCurrentAndUnshared(UserSnapshot expected, Set<GcsReference> objects) {
        User actual = users.findById(expected.id()).orElseThrow(() -> conflict("ACCOUNT_CHANGED"));
        if (!expected.equals(snapshot(actual))) throw conflict("ACCOUNT_CHANGED");
        boolean handledMeetings = related != null && related.enabled();
        if (handledMeetings) related.validate(expected);
        if (!handledMeetings && expected.currentMeetingId() != null && !expected.currentMeetingId().isBlank()) {
            throw conflict("MEETING_REVIEW_REQUIRED");
        }
        if (expected.email() == null || expected.email().isBlank()) throw AccountDeletionException.integration();
        // Case-insensitive collisions would make email-scoped OTP cleanup ambiguous.
        Long duplicates = jdbc.queryForObject("""
            select count(*) from users where id <> ? and
            (lower(btrim(email)) = lower(btrim(?)) or
             (cast(? as text) is not null and google_id = ?) or
             (cast(? as text) is not null and meshy_task_id = ?))
            """, Long.class, expected.id(), expected.email(), expected.googleId(), expected.googleId(),
            expected.meshyTaskId(), expected.meshyTaskId());
        if (duplicates == null || duplicates != 0) throw AccountDeletionException.integration();
        Long meetings = jdbc.queryForObject("""
            select count(*) from meetings m
            where lower(btrim(m.host_email)) = lower(btrim(?))
            or exists (select 1 from unnest(string_to_array(coalesce(m.participant_emails, ''), ',')) p(email)
                       where lower(btrim(p.email)) = lower(btrim(?)))
            """, Long.class, expected.email(), expected.email());
        if (!handledMeetings && (meetings == null || meetings != 0)) throw conflict("MEETING_REVIEW_REQUIRED");
        // Unknown User FKs require a reviewed dependency/retention handler, even when currently empty.
        Long unknownForeignKeys = jdbc.queryForObject("""
            select count(*) from pg_constraint c
            where c.contype = 'f' and c.confrelid = to_regclass('users')
              and not (c.conrelid = to_regclass('account_deletion_requests') and c.confdeltype = 'n')
              and not coalesce((c.confdeltype = 'c'
                and c.conrelid in (to_regclass('account_deletion_sessions'),
                    to_regclass('account_deletion_owned_objects'), to_regclass('account_deletion_cleanup_plans'),
                    to_regclass('account_deletion_google_links'), to_regclass('account_deletion_legacy_objects'),
                    to_regclass('account_deletion_provider_tasks'), to_regclass('account_deletion_coverage'))
                and c.conkey = array[(select attnum from pg_attribute
                    where attrelid=c.conrelid and attname='user_id')]::smallint[]
                and c.confkey = array[(select attnum from pg_attribute
                    where attrelid=c.confrelid and attname='id')]::smallint[]), false)
            """, Long.class);
        if (unknownForeignKeys == null || unknownForeignKeys != 0) throw AccountDeletionException.integration();
        if (!objects.isEmpty()) {
            RowCallbackHandler check = row -> {
                var reference = GcsReference.parse(row.getString(1));
                if (reference.isPresent() && objects.contains(reference.get())) {
                    throw conflict("STORAGE_OWNERSHIP_UNVERIFIED");
                }
            };
            // Canonical comparison recognizes gs://, path/virtual-host URLs and signed URL aliases.
            jdbc.query("""
                select image_url from users where id <> ?
                union all select avatar_url from users where id <> ?
                union all select rigged_glb_url from users where id <> ?
                union all select walking_glb_url from users where id <> ?
                union all select idle_glb_url from users where id <> ?
                union all select sitting_glb_url from users where id <> ?
                union all select recording_url from meetings
                union all select pdf_url from meetings
                union all select notes_url from meetings
                union all select ppt_url from meetings
                """, check, expected.id(), expected.id(), expected.id(), expected.id(), expected.id(), expected.id());
        }
    }
    public static UserSnapshot snapshot(User user) {
        return new UserSnapshot(user.getId(), user.getEmail(),
            Stream.of(user.getImageUrl(), user.getAvatarUrl(), user.getRiggedGlbUrl(), user.getWalkingGlbUrl(),
                user.getIdleGlbUrl(), user.getSittingGlbUrl()).filter(s -> s != null && !s.isBlank()).distinct().toList(),
            user.getMeshyTaskId(), user.getGoogleId(), user.getCurrentMeetingId());
    }
    private AccountDeletionException conflict(String code) {
        return new AccountDeletionException(HttpStatus.CONFLICT, code);
    }
}
