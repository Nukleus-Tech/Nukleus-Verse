package com.nukleus.vrmeeting.accountdeletion;

import com.nukleus.vrmeeting.controller.AdminUserController;
import com.nukleus.vrmeeting.model.User;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.hibernate.mapping.Table;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountDeletionUnitTest {
    static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static UsernamePasswordAuthenticationToken userAuthentication(long id, Instant at) {
        return new UsernamePasswordAuthenticationToken(
            new AuthenticatedAccountResolver.AccountPrincipal(id, at), null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
    @Test void defaultsAreSevenDaysAndAllFeaturesOff() {
        var p = new AccountDeletionProperties();
        assertEquals(Duration.ofHours(168), p.getGracePeriod());
        assertFalse(p.isApiEnabled()); assertFalse(p.isSchedulerEnabled()); assertFalse(p.isDestructiveEnabled());
        assertTrue(p.isDurationConfigurationValid());
        p.setGracePeriod(Duration.ZERO); assertFalse(p.isDurationConfigurationValid());
    }
    @Test void schedulerDoesNotStartByDefaultOrWithOnlyOneSwitch() {
        var p = new AccountDeletionProperties();
        var processor = mock(AccountDeletionProcessor.class);
        var scheduler = new AccountDeletionScheduler(p, processor);
        scheduler.start(); assertFalse(scheduler.isRunning());
        p.setSchedulerEnabled(true); scheduler.start(); assertFalse(scheduler.isRunning());
        verifyNoInteractions(processor);
    }
    @Test void currentAdminTokenAndAnonymousCannotBecomeUserIdentity() {
        var resolver = new AuthenticatedAccountResolver(CLOCK, new AccountDeletionProperties());
        assertThrows(AccountDeletionException.class, () -> resolver.resolve(null, true));
        var admin = new UsernamePasswordAuthenticationToken("synthetic@example.invalid", null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThrows(AccountDeletionException.class, () -> resolver.resolve(admin, true));
        assertEquals(42, resolver.resolve(userAuthentication(42, NOW), true));
    }
    @Test void staleFutureMissingAuthenticationRejectedButStatusAllowsStaleAuth() {
        var resolver = new AuthenticatedAccountResolver(CLOCK, new AccountDeletionProperties());
        for (Instant at : Arrays.asList(NOW.minusSeconds(601), NOW.plusSeconds(1), null)) {
            assertThrows(AccountDeletionException.class, () -> resolver.resolve(userAuthentication(42, at), true));
        }
        assertEquals(42, resolver.resolve(userAuthentication(42, NOW.minusSeconds(601)), false));
    }
    @Test void exactlySevenDaysAcrossDaylightSavingBoundary() {
        Instant at = Instant.parse("2026-03-07T17:00:00Z");
        var r = AccountDeletionRequest.pending(42, at, at.plus(Duration.ofDays(7)));
        assertEquals(604800, Duration.between(r.getRequestedAt(), r.getScheduledDeletionAt()).toSeconds());
        assertFalse(r.due(at.plusSeconds(604799), 5));
        assertTrue(r.due(at.plusSeconds(604800), 5));
    }
    @Test void retryEligibilityAndCompletionAnonymization() {
        var r = AccountDeletionRequest.pending(42, NOW.minus(Duration.ofDays(7)), NOW);
        UUID first = r.claim(NOW);
        r.fail(NOW, "STORAGE_FAILURE", NOW.plusSeconds(60));
        assertFalse(r.owns(first)); assertFalse(r.due(NOW, 5)); assertTrue(r.due(NOW.plusSeconds(60), 5));
        UUID second = r.claim(NOW.plusSeconds(60));
        assertNotEquals(first, second); assertEquals(2, r.getRetryCount());
        r.complete(NOW.plusSeconds(61));
        assertNull(r.getUserId()); assertNull(r.getLastError()); assertFalse(r.active());
        assertFalse(r.due(NOW.plusSeconds(62), 5));
    }
    @Test void schemaFilterExcludesOnlyNewTable() {
        var filter = new AccountDeletionSchemaFilter();
        Table deletion = new Table("account-deletion-test"); deletion.setName("account_deletion_requests");
        Table users = new Table("account-deletion-test"); users.setName("users");
        Table meetings = new Table("account-deletion-test"); meetings.setName("meetings");
        assertFalse(filter.getMigrateFilter().includeTable(deletion));
        assertFalse(filter.getCreateFilter().includeTable(deletion));
        assertFalse(filter.getDropFilter().includeTable(deletion));
        assertTrue(filter.getMigrateFilter().includeTable(users));
        assertTrue(filter.getMigrateFilter().includeTable(meetings));
        assertTrue(filter.getValidateFilter().includeTable(deletion));
    }
    @Test void gatewayDefaultsFailClosed() {
        var gateway = new AccountDeletionConfiguration().closedAccountDeletionGateway();
        assertThrows(AccountDeletionException.class, gateway::requireRequestProtection);
        assertThrows(AccountDeletionException.class, () -> gateway.revokeSessions(42));
        assertThrows(AccountDeletionException.class, () -> gateway.prepareCleanup(null));
    }
    @Test void pendingPolicyCoversFailedAndProcessingButNotCancelled() {
        var repository = mock(AccountDeletionRepository.class);
        var policy = new AccountDeletionAccessPolicy(repository);
        var r = AccountDeletionRequest.pending(42, NOW, NOW.plusSeconds(604800));
        when(repository.findFirstByUserIdOrderByRequestedAtDescIdDesc(42L)).thenReturn(Optional.of(r));
        assertThrows(AccountDeletionException.class, () -> policy.requireNormalAccess(42));
        r.claim(NOW); assertThrows(AccountDeletionException.class, () -> policy.requireNormalAccess(42));
        r.fail(NOW, "STORAGE_FAILURE", null);
        assertThrows(AccountDeletionException.class, () -> policy.requireNormalAccess(42));
        r.cancel(NOW); assertDoesNotThrow(() -> policy.requireNormalAccess(42));
    }
    @Test void existingAdminBlockAndUnblockStillWorkIndependently() {
        var controller = new AdminUserController();
        var users = mock(UserRepository.class);
        ReflectionTestUtils.setField(controller, "userRepository", users);
        User user = new User(); user.setAccountStatus("ACTIVE");
        when(users.findById(42L)).thenReturn(Optional.of(user));
        assertEquals(true, controller.blockUser(42L).get("success"));
        assertEquals("BLOCKED", user.getAccountStatus());
        assertEquals(true, controller.unblockUser(42L).get("success"));
        assertEquals("ACTIVE", user.getAccountStatus());
        verify(users, times(2)).save(user);
    }
}
