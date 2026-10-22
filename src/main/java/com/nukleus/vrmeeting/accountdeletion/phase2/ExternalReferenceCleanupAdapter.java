package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.UserSnapshot;
import java.util.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;

/**
 * Orchestrates reviewed provider clients with durable retry receipts, supplied by the integration configuration.
 * Google application-grant revocation is NOT deletion of a person's Google account.
 */
public final class ExternalReferenceCleanupAdapter {
    private final Phase2Policy policy;
    private final AccountDeletionProperties properties;
    private final CleanupJournal journal;
    private final Map<ExternalProvider, ExternalReferenceClient> clients;
    public ExternalReferenceCleanupAdapter(Phase2Policy policy, AccountDeletionProperties properties,
            CleanupJournal journal, Map<ExternalProvider, ExternalReferenceClient> clients) {
        this.policy = policy; this.properties = properties; this.journal = journal;
        this.clients = Map.copyOf(clients);
    }
    public void preflight(ApprovedCleanupPlan plan, UserSnapshot user) {
        policy.requireCleanup(properties);
        plan.requireMatches(plan.requestId(), user);
        // Verify ALL unfinished providers before any provider is called destructively.
        for (var reference : references(user).entrySet()) {
            if (!journal.externalCleanupCompleted(plan.planId(), reference.getKey())) {
                var client = clients.get(reference.getKey());
                if (client == null) throw AccountDeletionException.integration();
                client.verifyRemovable(user.id(), reference.getValue());
            }
        }
    }
    public void cleanup(ApprovedCleanupPlan plan, UserSnapshot user) {
        preflight(plan, user);
        for (var reference : references(user).entrySet()) {
            if (journal.externalCleanupCompleted(plan.planId(), reference.getKey())) continue;
            clients.get(reference.getKey()).removeApplicationReference(user.id(), reference.getValue(), plan.planId());
            // If recording fails after provider success, the same planId is retried idempotently.
            journal.recordExternalCleanupCompleted(plan.planId(), reference.getKey());
        }
    }
    private Map<ExternalProvider, String> references(UserSnapshot user) {
        Map<ExternalProvider, String> references = new EnumMap<>(ExternalProvider.class);
        if (user.googleId() != null && !user.googleId().isBlank()) {
            references.put(ExternalProvider.GOOGLE_APPLICATION_GRANT, user.googleId());
        }
        if (user.meshyTaskId() != null && !user.meshyTaskId().isBlank()) {
            references.put(ExternalProvider.MESHY_TASK, user.meshyTaskId());
        }
        clients.forEach((provider, client) -> {
            if (!references.containsKey(provider) && client.hasAdditionalReferences(user.id())) references.put(provider, "");
        });
        return references;
    }
}
