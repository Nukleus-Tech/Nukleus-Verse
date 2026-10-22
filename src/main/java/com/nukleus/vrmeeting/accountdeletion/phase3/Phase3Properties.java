package com.nukleus.vrmeeting.accountdeletion.phase3;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.Set;

/** Opt-in integration, independent of the three existing default-off deletion flags. */
@ConfigurationProperties(prefix = "nukleus.account-deletion.integration")
public class Phase3Properties {
    private boolean enabled;
    private Duration sessionLifetime = Duration.ofHours(8);
    private Set<String> archiveHosts = Set.of();
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Duration getSessionLifetime() { return sessionLifetime; }
    public void setSessionLifetime(Duration value) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofHours(24)) > 0)
            throw new IllegalArgumentException("Session lifetime must be positive and at most 24 hours");
        sessionLifetime = value;
    }
    public Set<String> getArchiveHosts() { return archiveHosts; }
    public void setArchiveHosts(Set<String> value) { archiveHosts = Set.copyOf(value); }
}

