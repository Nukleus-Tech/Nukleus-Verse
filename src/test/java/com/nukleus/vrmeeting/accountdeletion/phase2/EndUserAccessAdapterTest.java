package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.Instant;
import java.util.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2TestSupport.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.PendingDeletionAccessAdapter.Operation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EndUserAccessAdapterTest {
    SessionAuthority sessions; UserRepository users; AccountDeletionRepository requests;
    EndUserIdentityAdapter identity; PendingDeletionAccessAdapter access;
    @BeforeEach void setup() {
        sessions = mock(SessionAuthority.class); users = mock(UserRepository.class);
        requests = mock(AccountDeletionRepository.class);
        identity = new EndUserIdentityAdapter(ENABLED, sessions, users, properties(), CLOCK);
        access = new PendingDeletionAccessAdapter(identity, requests);
        when(users.findById(42L)).thenReturn(Optional.of(user(42)));
        when(sessions.verifiedSession(any())).thenReturn(Optional.of(session(42, NOW, SessionScope.FULL)));
    }
    @Test void disabledAdaptersMakeNoIdentityLookup() {
        var disabled = new EndUserIdentityAdapter(new Phase2Policy(), sessions, users, properties(), CLOCK);
        assertThrows(AccountDeletionException.class, () -> disabled.identify(authentication(42, NOW), true));
        verifyNoInteractions(users, sessions);
    }
    @Test void anonymousAndClientSelectedStringIdentitiesAreRejected() {
        assertThrows(AccountDeletionException.class, () -> identity.identify(null, false));
        var clientId = new UsernamePasswordAuthenticationToken("42", null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThrows(AccountDeletionException.class, () -> identity.identify(clientId, false));
        verifyNoInteractions(users, sessions);
    }
    @Test void adminAndMixedRoleTokensAreNeverEndUserEvidence() {
        var admin = new UsernamePasswordAuthenticationToken("synthetic42@example.invalid", null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var mixed = new UsernamePasswordAuthenticationToken(
            new AuthenticatedAccountResolver.AccountPrincipal(42, NOW), null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThrows(AccountDeletionException.class, () -> identity.identify(admin, false));
        assertThrows(AccountDeletionException.class, () -> identity.identify(mixed, false));
        verifyNoInteractions(users, sessions);
    }
    @Test void principalAloneDoesNotProveSessionOwnership() {
        when(sessions.verifiedSession(any())).thenReturn(Optional.empty());
        assertThrows(AccountDeletionException.class, () -> identity.identify(authentication(42, NOW), false));
        verifyNoInteractions(users);
    }
    @Test void mismatchedRevokedExpiredOrFutureEvidenceRejected() {
        for (VerifiedSession evidence : List.of(
            session(99, NOW, SessionScope.FULL),
            new VerifiedSession(42, NOW, NOW.plusSeconds(300), SessionScope.FULL, true),
            new VerifiedSession(42, NOW, NOW, SessionScope.FULL, false),
            session(42, NOW.plusSeconds(1), SessionScope.FULL),
            session(42, NOW.minusSeconds(1), SessionScope.FULL))) {
            when(sessions.verifiedSession(any())).thenReturn(Optional.of(evidence));
            assertThrows(AccountDeletionException.class, () -> identity.identify(authentication(42, NOW), false));
        }
        verifyNoInteractions(users);
    }
    @Test void identityComesFromVerifiedSessionAndImmutableUserIdOnly() {
        assertEquals(42, identity.identify(authentication(42, NOW), true).userId());
        verify(users).findById(42L); verifyNoMoreInteractions(users);
    }
    @Test void deletedUserCannotUseAnOtherwiseValidSession() {
        when(users.findById(42L)).thenReturn(Optional.empty());
        assertEquals("ACCOUNT_NOT_FOUND", assertThrows(AccountDeletionException.class,
            () -> identity.identify(authentication(42, NOW), false)).getCode());
    }
    @Test void pendingProcessingAndFailedUsersCannotUseOrdinaryOperations() {
        for (var state : List.of(AccountDeletionRequest.Status.PENDING,
                AccountDeletionRequest.Status.PROCESSING, AccountDeletionRequest.Status.FAILED)) {
            when(requests.findFirstByUserIdOrderByRequestedAtDescIdDesc(42L)).thenReturn(Optional.of(request(state)));
            assertThrows(AccountDeletionException.class, () -> access.authorize(authentication(42, NOW), NORMAL));
            assertThrows(AccountDeletionException.class, () -> access.authorize(authentication(42, NOW), REQUEST_DELETION));
            assertEquals(42, access.authorize(authentication(42, NOW), DELETION_STATUS));
        }
    }
    @Test void cancellationRequiresRecentCredentialsNotRecentRefresh() {
        var old = NOW.minusSeconds(601);
        when(sessions.verifiedSession(any())).thenReturn(Optional.of(session(42, old, SessionScope.DELETION_ONLY)));
        assertEquals(42, access.authorize(authentication(42, old), DELETION_STATUS));
        assertEquals("RECENT_AUTHENTICATION_REQUIRED", assertThrows(AccountDeletionException.class,
            () -> access.authorize(authentication(42, old), CANCEL_DELETION)).getCode());
    }
    @Test void cancellationNeverUpgradesRestrictedSessionToOrdinaryAccess() {
        when(sessions.verifiedSession(any())).thenReturn(Optional.of(session(42, NOW, SessionScope.DELETION_ONLY)));
        when(requests.findFirstByUserIdOrderByRequestedAtDescIdDesc(42L))
            .thenReturn(Optional.of(request(AccountDeletionRequest.Status.CANCELLED)));
        assertEquals(42, access.authorize(authentication(42, NOW), CANCEL_DELETION));
        assertThrows(AccountDeletionException.class, () -> access.authorize(authentication(42, NOW), NORMAL));
    }
    @Test void facadeDelegatesOnlyVerifiedOwnerWithoutClientIdentityParameter() {
        var phase1 = mock(AccountDeletionService.class);
        var facade = new AuthenticatedDeletionFacade(access, phase1);
        facade.cancel(authentication(42, NOW)); facade.status(authentication(42, NOW));
        facade.request(authentication(42, NOW), true);
        verify(phase1).cancel(42); verify(phase1).status(42); verify(phase1).request(42);
        verifyNoMoreInteractions(phase1);
    }
    @Test void databaseOrRevocationServiceFailureCannotGrantAccess() {
        when(sessions.verifiedSession(any())).thenThrow(new IllegalStateException("synthetic unavailable"));
        assertThrows(IllegalStateException.class, () -> access.authorize(authentication(42, NOW), NORMAL));
        verifyNoInteractions(users, requests);
    }
}
