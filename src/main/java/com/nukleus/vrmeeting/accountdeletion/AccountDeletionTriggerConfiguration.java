package com.nukleus.vrmeeting.accountdeletion;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountDeletionTriggerProperties.class)
public class AccountDeletionTriggerConfiguration {
    @Bean AccountDeletionTriggerIdentity accountDeletionTriggerIdentity(
            AccountDeletionTriggerProperties properties, @Qualifier("accountDeletionClock") Clock clock) {
        // Separate audience from interactive Google Login; this verifier cannot mint user sessions.
        var verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
            .setAudience(List.of(properties.getAudience() == null ? "" : properties.getAudience()))
            .setClock(clock::millis).setAcceptableTimeSkewSeconds(30).build();
        return new AccountDeletionTriggerIdentity(properties, verifier, clock);
    }
}
