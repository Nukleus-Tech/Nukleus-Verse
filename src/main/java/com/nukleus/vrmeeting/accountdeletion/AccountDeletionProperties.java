package com.nukleus.vrmeeting.accountdeletion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.Set;

@Validated
@ConfigurationProperties(prefix = "nukleus.account-deletion")
public class AccountDeletionProperties {
    private boolean apiEnabled = false;
    private boolean schedulerEnabled = false;
    private boolean destructiveEnabled = false;
    @NotNull private Duration gracePeriod = Duration.ofHours(168);
    @NotNull private Duration recentAuthentication = Duration.ofMinutes(10);
    @NotNull private Duration retryDelay = Duration.ofMinutes(15);
    @Min(1) @Max(10) private int maxAttempts = 5;
    @Min(1) @Max(100) private int batchSize = 10;
    @NotNull private Set<String> allowedBuckets = Set.of();

    @AssertTrue(message = "All account-deletion durations must be positive")
    public boolean isDurationConfigurationValid() {
        return positive(gracePeriod) && positive(recentAuthentication) && positive(retryDelay);
    }
    private boolean positive(Duration d) { return d != null && !d.isNegative() && !d.isZero(); }
    public boolean isApiEnabled() { return apiEnabled; }
    public void setApiEnabled(boolean v) { apiEnabled = v; }
    public boolean isSchedulerEnabled() { return schedulerEnabled; }
    public void setSchedulerEnabled(boolean v) { schedulerEnabled = v; }
    public boolean isDestructiveEnabled() { return destructiveEnabled; }
    public void setDestructiveEnabled(boolean v) { destructiveEnabled = v; }
    public Duration getGracePeriod() { return gracePeriod; }
    public void setGracePeriod(Duration v) { gracePeriod = v; }
    public Duration getRecentAuthentication() { return recentAuthentication; }
    public void setRecentAuthentication(Duration v) { recentAuthentication = v; }
    public Duration getRetryDelay() { return retryDelay; }
    public void setRetryDelay(Duration v) { retryDelay = v; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int v) { maxAttempts = v; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int v) { batchSize = v; }
    public Set<String> getAllowedBuckets() { return allowedBuckets; }
    public void setAllowedBuckets(Set<String> v) { allowedBuckets = Set.copyOf(v); }
}
