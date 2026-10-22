package com.nukleus.vrmeeting.accountdeletion;

import com.nukleus.vrmeeting.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.Objects;

@Service
public class AccountDeletionService {
    private final AccountDeletionRepository repository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final AccountDeletionProperties properties;
    private final AccountDeletionSafetyGateway safety;
    private final Clock clock;
    public AccountDeletionService(AccountDeletionRepository repository, EntityManager entityManager,
            PlatformTransactionManager manager, AccountDeletionProperties properties,
            AccountDeletionSafetyGateway safety, @Qualifier("accountDeletionClock") Clock clock) {
        this.repository = repository; this.entityManager = entityManager;
        this.transactions = new TransactionTemplate(manager); this.properties = properties;
        this.safety = safety; this.clock = clock;
    }
    void requireEnabled() {
        if (!properties.isApiEnabled()) {
            throw new AccountDeletionException(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_DELETION_DISABLED");
        }
    }
    private void lockUser(long userId) {
        if (entityManager.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE) == null) {
            throw new AccountDeletionException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND");
        }
    }
    public AccountDeletionDtos.RequestResult request(long userId) {
        requireEnabled();
        safety.requireRequestProtection();
        var result = Objects.requireNonNull(transactions.execute(tx -> {
            // Serialize all requests for this user, even before a deletion row exists.
            lockUser(userId);
            var previous = repository.findFirstByUserIdOrderByRequestedAtDescIdDesc(userId).orElse(null);
            if (previous != null && previous.active()) {
                return new AccountDeletionDtos.RequestResult(AccountDeletionDtos.StatusResponse.from(previous), false);
            }
            var now = clock.instant();
            var request = AccountDeletionRequest.pending(userId, now, now.plus(properties.getGracePeriod()));
            repository.saveAndFlush(request);
            return new AccountDeletionDtos.RequestResult(AccountDeletionDtos.StatusResponse.from(request), true);
        }));
        // Outside transaction. Failure leaves PENDING intact; a retry attempts revocation again.
        // Integration must also check the request table on each protected operation.
        safety.revokeSessions(userId);
        return result;
    }
    public AccountDeletionDtos.StatusResponse status(long userId) {
        requireEnabled();
        return Objects.requireNonNull(transactions.execute(tx -> {
            lockUser(userId);
            return AccountDeletionDtos.StatusResponse.from(
                    repository.findFirstByUserIdOrderByRequestedAtDescIdDesc(userId).orElse(null));
        }));
    }
    public AccountDeletionDtos.StatusResponse cancel(long userId) {
        requireEnabled();
        return Objects.requireNonNull(transactions.execute(tx -> {
            lockUser(userId);
            var latest = repository.findFirstByUserIdOrderByRequestedAtDescIdDesc(userId)
                .orElseThrow(() -> AccountDeletionException.conflict("NO_PENDING_DELETION"));
            var request = repository.lockById(latest.getId()).orElseThrow();
            if (request.getStatus() == AccountDeletionRequest.Status.CANCELLED) {
                return AccountDeletionDtos.StatusResponse.from(request);
            }
            if (request.getStatus() != AccountDeletionRequest.Status.PENDING ||
                !clock.instant().isBefore(request.getScheduledDeletionAt())) {
                throw AccountDeletionException.conflict("CANCELLATION_WINDOW_CLOSED");
            }
            request.cancel(clock.instant());
            repository.saveAndFlush(request);
            return AccountDeletionDtos.StatusResponse.from(request);
        }));
    }
}
