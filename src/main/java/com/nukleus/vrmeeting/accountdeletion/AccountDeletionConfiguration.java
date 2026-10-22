package com.nukleus.vrmeeting.accountdeletion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountDeletionProperties.class)
public class AccountDeletionConfiguration {
    @Bean(name = "accountDeletionClock")
    Clock accountDeletionClock() { return Clock.systemUTC(); }

    @Bean
    HibernatePropertiesCustomizer accountDeletionDdlProtection() {
        return properties -> {
            String key = "hibernate.hbm2ddl.schema_filter_provider";
            if (properties.containsKey(key)) {
                throw new IllegalStateException("Compose existing schema filter with account-deletion protection");
            }
            properties.put(key, new AccountDeletionSchemaFilter());
        };
    }

    @Bean
    @ConditionalOnMissingBean(AccountDeletionSafetyGateway.class)
    AccountDeletionSafetyGateway closedAccountDeletionGateway() {
        return new AccountDeletionSafetyGateway() {
            public void requireRequestProtection() { throw AccountDeletionException.integration(); }
            public void revokeSessions(long userId) { throw AccountDeletionException.integration(); }
            public CleanupInventory prepareCleanup(AccountDeletionSafetyGateway.UserSnapshot user) {
                throw AccountDeletionException.integration();
            }
            public void cleanupExternalIdentities(UserSnapshot user) {
                throw AccountDeletionException.integration();
            }
        };
    }
}
