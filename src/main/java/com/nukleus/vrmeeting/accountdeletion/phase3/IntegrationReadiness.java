package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.nukleus.vrmeeting.accountdeletion.AccountDeletionException;
import com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.ProtectionReadiness;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable rollout latch + actual schema checks; a property alone cannot authorize deletion. */
public final class IntegrationReadiness implements ProtectionReadiness {
    private final JdbcTemplate jdbc;
    private final Phase3Properties properties;
    private final CleanupIntegrationProperties cleanup;
    public IntegrationReadiness(JdbcTemplate jdbc, Phase3Properties properties) {
        this(jdbc, properties, new CleanupIntegrationProperties());
    }
    public IntegrationReadiness(JdbcTemplate jdbc, Phase3Properties properties, CleanupIntegrationProperties cleanup) {
        this.jdbc = jdbc; this.properties = properties; this.cleanup = cleanup;
    }
    public void checkStartup() {
        String table = jdbc.queryForObject("select to_regclass('account_deletion_integration_state')::text", String.class);
        if (table == null) {
            if (properties.isEnabled()) throw AccountDeletionException.integration();
            return;
        }
        Boolean required = jdbc.queryForObject(
            "select enforcement_required from account_deletion_integration_state where singleton", Boolean.class);
        if (Boolean.TRUE.equals(required) && !properties.isEnabled())
            throw new IllegalStateException("Account-deletion enforcement cannot be disabled after rollout");
        if (properties.isEnabled()) requireSchema();
    }
    public void requireSchema() {
        if (cleanup.isAutomaticPreparationEnabled() || !cleanup.getGoogleClientIds().isEmpty()) {
            for (String name : new String[]{"account_deletion_google_challenges", "account_deletion_google_links",
                    "account_deletion_legacy_objects", "account_deletion_provider_tasks", "account_deletion_provider_assets",
                    "account_deletion_coverage", "account_deletion_plan_bindings"}) {
                if (jdbc.queryForObject("select to_regclass(?)::text", String.class, name) == null)
                    throw AccountDeletionException.integration();
            }
        }
        for (String name : new String[]{"account_deletion_requests", "account_deletion_sessions",
                "account_deletion_owned_objects", "account_deletion_cleanup_plans",
                "account_deletion_generation_receipts", "account_deletion_provider_receipts"}) {
            if (jdbc.queryForObject("select to_regclass(?)::text", String.class, name) == null)
                throw AccountDeletionException.integration();
        }
    }
    @Override public void requireReady() {
        if (!properties.isEnabled()) throw AccountDeletionException.integration();
        requireSchema();
        Boolean approved = jdbc.queryForObject("""
            select schema_version=1 and enforcement_required and rollout_approved_at is not null
            from account_deletion_integration_state where singleton
            """, Boolean.class);
        if (!Boolean.TRUE.equals(approved)) throw AccountDeletionException.integration();
    }
}

