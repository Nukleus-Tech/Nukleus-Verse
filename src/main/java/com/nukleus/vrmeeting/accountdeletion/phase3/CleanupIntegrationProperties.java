package com.nukleus.vrmeeting.accountdeletion.phase3;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Set;

/** Additional capabilities are opt-in; existing API/worker/destructive gates still apply. */
@ConfigurationProperties(prefix="nukleus.account-deletion.cleanup")
public class CleanupIntegrationProperties {
    private boolean automaticPreparationEnabled, providerCallsEnabled, anonymizeMeetingReferences;
    private Set<String> googleClientIds=Set.of();
    private String meshyApiKey="", encryptionKey="";
    public boolean isAutomaticPreparationEnabled(){return automaticPreparationEnabled;}
    public void setAutomaticPreparationEnabled(boolean v){automaticPreparationEnabled=v;}
    public boolean isProviderCallsEnabled(){return providerCallsEnabled;}
    public void setProviderCallsEnabled(boolean v){providerCallsEnabled=v;}
    public boolean isAnonymizeMeetingReferences(){return anonymizeMeetingReferences;}
    public void setAnonymizeMeetingReferences(boolean v){anonymizeMeetingReferences=v;}
    public Set<String> getGoogleClientIds(){return googleClientIds;}
    public void setGoogleClientIds(Set<String> v){googleClientIds=Set.copyOf(v);}
    public String getMeshyApiKey(){return meshyApiKey;}
    public void setMeshyApiKey(String v){meshyApiKey=v;}
    public String getEncryptionKey(){return encryptionKey;}
    public void setEncryptionKey(String v){encryptionKey=v;}
}
