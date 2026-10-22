package com.nukleus.vrmeeting.accountdeletion;

import com.nukleus.vrmeeting.model.User;
import jakarta.persistence.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountDeletionWorkStore {
    public record Claim(long requestId, long userId, UUID token,
                        AccountDeletionSafetyGateway.UserSnapshot user) {}
    private final AccountDeletionRepository repository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final AccountDeletionProperties properties;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nukleus.vrmeeting.accountdeletion.phase3.RelatedAccountDataCleanup related;
    public AccountDeletionWorkStore(AccountDeletionRepository repository, EntityManager entityManager,
            PlatformTransactionManager manager, AccountDeletionProperties properties,
            @Qualifier("accountDeletionClock") Clock clock) {
        this.repository = repository; this.entityManager = entityManager;
        this.transactions = new TransactionTemplate(manager); this.properties = properties; this.clock = clock;
    }
    private void enabled() {
        if (!properties.isSchedulerEnabled() || !properties.isDestructiveEnabled()) {
            throw AccountDeletionException.integration();
        }
    }
    public Optional<Claim> claim(long requestId) {
        enabled();
        return transactions.execute(tx -> {
            var ownerId = repository.findOwnerId(requestId).orElse(null);
            if (ownerId == null) return Optional.empty();
            // Same lock order as request/cancel: user, then deletion request.
            User user = entityManager.find(User.class, ownerId, LockModeType.PESSIMISTIC_WRITE);
            var r = repository.lockById(requestId).orElse(null);
            if (user == null || r == null || !r.due(clock.instant(), properties.getMaxAttempts())) {
                return Optional.empty();
            }
            // A process crash consumes an attempt too; recovery is bounded.
            if (r.getRetryCount() >= properties.getMaxAttempts()) {
                r.fail(clock.instant(), "ATTEMPTS_EXHAUSTED", null);
                repository.saveAndFlush(r);
                return Optional.empty();
            }
            UUID token = r.claim(clock.instant());
            repository.saveAndFlush(r);
            return Optional.of(new Claim(requestId, user.getId(), token,
                    AccountDeletionSafetyGateway.UserSnapshot.from(user)));
        });
    }
    public void fail(Claim claim, String code, boolean retryable) {
        enabled();
        transactions.executeWithoutResult(tx -> {
            var r = repository.lockById(claim.requestId()).orElse(null);
            if (r == null || !r.owns(claim.token())) return;
            var now = clock.instant();
            var retryAt = retryable && r.getRetryCount() < properties.getMaxAttempts()
                ? now.plus(properties.getRetryDelay().multipliedBy(r.getRetryCount())) : null;
            r.fail(now, code, retryAt);
            repository.saveAndFlush(r);
        });
    }
    public void complete(Claim claim) {
        enabled();
        transactions.executeWithoutResult(tx -> {
            User user = entityManager.find(User.class, claim.userId(), LockModeType.PESSIMISTIC_WRITE);
            var r = repository.lockById(claim.requestId()).orElseThrow();
            if (!r.owns(claim.token()) || r.getUserId() == null || r.getUserId() != claim.userId() ||
                r.getScheduledDeletionAt().isAfter(clock.instant())) {
                throw AccountDeletionException.conflict("CLAIM_LOST");
            }
            if (user == null) throw AccountDeletionException.conflict("ACCOUNT_CHANGED");
            // Fail closed if any avatar/identity reference changed while external cleanup ran.
            if (!claim.user().equals(AccountDeletionSafetyGateway.UserSnapshot.from(user))) {
                throw AccountDeletionException.conflict("ACCOUNT_CHANGED");
            }
            requireNoMeetingReferences(user);
            if (related != null && related.enabled()) related.cleanup(claim.user());
            // Shared meetings use email strings, NOT FKs. Never delete their assets or other members.
            if (user.getEmail() != null && !user.getEmail().isBlank()) {
                // Only the verified user's OTP records. Admin records are never touched.
                entityManager.createQuery("delete from EmailOtp o where lower(o.email) = lower(:email)")
                    .setParameter("email", user.getEmail()).executeUpdate();
            }
            r.complete(clock.instant());
            repository.saveAndFlush(r);
            entityManager.remove(user);
            entityManager.flush();
            // FK ON DELETE SET NULL anonymizes all older requests in the same transaction.
        });
    }

    /** Short DB-only preflight BEFORE external deletion; complete() repeats the checks. */
    public void validateCleanup(Claim claim) {
        enabled();
        transactions.executeWithoutResult(tx -> {
            User user = entityManager.find(User.class, claim.userId(), LockModeType.PESSIMISTIC_WRITE);
            var r = repository.lockById(claim.requestId()).orElseThrow();
            if (!r.owns(claim.token()) || r.getUserId() == null || r.getUserId() != claim.userId() ||
                r.getScheduledDeletionAt().isAfter(clock.instant()) || user == null ||
                !claim.user().equals(AccountDeletionSafetyGateway.UserSnapshot.from(user))) {
                throw AccountDeletionException.conflict("CLAIM_LOST");
            }
            requireNoMeetingReferences(user);
        });
    }

    private void requireNoMeetingReferences(User user) {
        if (related != null && related.enabled()) {
            related.validate(AccountDeletionSafetyGateway.UserSnapshot.from(user));
            return;
        }
        if (user.getCurrentMeetingId() != null && !user.getCurrentMeetingId().isBlank()) {
            throw AccountDeletionException.conflict("MEETING_REVIEW_REQUIRED");
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) return;
        Number count = (Number) entityManager.createNativeQuery("""
            select count(*) from meetings m
            where lower(m.host_email) = lower(:email)
               or exists (select 1 from unnest(string_to_array(
                   coalesce(m.participant_emails, ''), ',')) p(email)
                   where lower(btrim(p.email)) = lower(:email))
            """).setParameter("email", user.getEmail()).getSingleResult();
        if (count.longValue() != 0) throw AccountDeletionException.conflict("MEETING_REVIEW_REQUIRED");
    }
}
