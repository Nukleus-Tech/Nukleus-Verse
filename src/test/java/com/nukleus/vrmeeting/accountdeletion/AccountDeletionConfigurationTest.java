package com.nukleus.vrmeeting.accountdeletion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountDeletionConfigurationTest {
    @Test void documentedEnvironmentVariableNamesBind() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            Map.of("NUKLEUS_ACCOUNTDELETION_GRACEPERIOD", "168h",
                "NUKLEUS_ACCOUNTDELETION_APIENABLED", "true",
                "NUKLEUS_ACCOUNTDELETION_SCHEDULERENABLED", "true",
                "NUKLEUS_ACCOUNTDELETION_DESTRUCTIVEENABLED", "false",
                "NUKLEUS_ACCOUNTDELETION_ALLOWEDBUCKETS", "synthetic-bucket")));
        var bound = new Binder(ConfigurationPropertySources.get(environment))
            .bind("nukleus.account-deletion", Bindable.of(AccountDeletionProperties.class)).get();
        assertEquals(Duration.ofHours(168), bound.getGracePeriod());
        assertTrue(bound.isApiEnabled()); assertTrue(bound.isSchedulerEnabled());
        assertFalse(bound.isDestructiveEnabled());
        assertEquals(Set.of("synthetic-bucket"), bound.getAllowedBuckets());
    }
    @Test void ddlCustomizerOnlyAddsNewTableProtection() {
        var properties = new HashMap<String, Object>();
        properties.put("hibernate.hbm2ddl.auto", "update");
        new AccountDeletionConfiguration().accountDeletionDdlProtection().customize(properties);
        assertEquals("update", properties.get("hibernate.hbm2ddl.auto"));
        assertInstanceOf(AccountDeletionSchemaFilter.class,
            properties.get("hibernate.hbm2ddl.schema_filter_provider"));
        assertEquals(2, properties.size());
    }
}
