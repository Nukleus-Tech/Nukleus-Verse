package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.AccountDeletionException;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.*;
import java.util.*;

/** Server-side approval evidence, never a REST payload; returned only by a trusted durable journal. */
public record ApprovedCleanupPlan(UUID planId, long requestId, long userId, String snapshotFingerprint,
                                  List<OwnedObject> objects, Set<ReviewDomain> reviewedDomains) {
    public enum ReviewDomain {
        STORAGE_AND_HISTORICAL_OBJECTS, AUTHENTICATION_REFERENCES, MESHY_REFERENCES,
        RELATED_DATABASE_RECORDS, SHARED_MEETINGS_AND_ALIASES, LOGS_BACKUPS_AND_RETENTION
    }
    public ApprovedCleanupPlan {
        Objects.requireNonNull(planId);
        Objects.requireNonNull(snapshotFingerprint);
        objects = List.copyOf(objects);
        reviewedDomains = Set.copyOf(reviewedDomains);
        if (requestId <= 0 || userId <= 0) throw AccountDeletionException.integration();
    }
    public void requireMatches(long expectedRequestId, UserSnapshot snapshot) {
        if (requestId != expectedRequestId || userId != snapshot.id() ||
            !snapshotFingerprint.equals(CleanupFingerprint.of(snapshot)) ||
            !reviewedDomains.containsAll(EnumSet.allOf(ReviewDomain.class))) {
            throw AccountDeletionException.integration();
        }
        Set<OwnedObject> generations = new HashSet<>();
        for (OwnedObject object : objects) {
            if (object.userId() != userId || object.generation() <= 0 ||
                !generations.add(object)) {
                throw AccountDeletionException.integration();
            }
        }
    }
    @Override public String toString() { return "ApprovedCleanupPlan[redacted]"; }
}
