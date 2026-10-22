package com.nukleus.vrmeeting.accountdeletion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;

/** Dedicated machine identity, never an end-user/admin token. Default-off. */
@ConfigurationProperties(prefix = "nukleus.account-deletion.trigger")
public class AccountDeletionTriggerProperties {
    private boolean enabled;
    private String audience = "", serviceAccountSubject = "", serviceAccountEmail = "";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getAudience() { return audience; }
    public void setAudience(String value) { audience = value; }
    public String getServiceAccountSubject() { return serviceAccountSubject; }
    public void setServiceAccountSubject(String value) { serviceAccountSubject = value; }
    public String getServiceAccountEmail() { return serviceAccountEmail; }
    public void setServiceAccountEmail(String value) { serviceAccountEmail = value; }

    public boolean configured() {
        try {
            URI uri = URI.create(audience);
            return "https".equals(uri.getScheme()) && uri.getHost() != null &&
                uri.getRawUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null &&
                (uri.getPort() == -1 || uri.getPort() == 443) &&
                serviceAccountSubject != null && serviceAccountSubject.matches("[0-9]{10,30}") &&
                serviceAccountEmail != null && serviceAccountEmail.matches(
                    "[a-z][a-z0-9-]{4,28}[a-z0-9]@[a-z][a-z0-9-]+\\.iam\\.gserviceaccount\\.com");
        } catch (IllegalArgumentException | NullPointerException ignored) { return false; }
    }
}
