package com.nukleus.vrmeeting.accountdeletion;

import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import static com.nukleus.vrmeeting.accountdeletion.AccountDeletionUnitTest.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountDeletionControllerTest {
    AccountDeletionService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(AccountDeletionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AccountDeletionController(service,
            new AuthenticatedAccountResolver(CLOCK, new AccountDeletionProperties())))
            .setControllerAdvice(new AccountDeletionExceptionHandler()).build();
    }
    @Test void rejectsUnauthenticatedRequestWithoutServiceCall() throws Exception {
        mvc.perform(post("/api/account/deletion-request").contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirm\":true}")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(service);
    }
    @Test void usesOnlyServerPrincipalAndNeverQueryUserId() throws Exception {
        var response = new AccountDeletionDtos.StatusResponse("PENDING", NOW,
            NOW.plusSeconds(604800), null);
        when(service.request(42)).thenReturn(new AccountDeletionDtos.RequestResult(response, true));
        mvc.perform(post("/api/account/deletion-request?userId=999")
            .principal(userAuthentication(42, NOW)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirm\":true}")).andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.userId").doesNotExist())
            .andExpect(jsonPath("$.password").doesNotExist());
        verify(service).request(42); verifyNoMoreInteractions(service);
    }
    @Test void requiresExplicitConfirmation() throws Exception {
        mvc.perform(post("/api/account/deletion-request").principal(userAuthentication(42, NOW))
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void cancellationRequiresRecentAuthentication() throws Exception {
        mvc.perform(post("/api/account/cancel-deletion")
            .principal(userAuthentication(42, NOW.minusSeconds(601))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("RECENT_AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(service);
    }
    @Test void statusDoesNotRequireReauthentication() throws Exception {
        when(service.status(42)).thenReturn(new AccountDeletionDtos.StatusResponse("NONE", null, null, null));
        mvc.perform(get("/api/account/deletion-status").principal(userAuthentication(42, Instant.EPOCH)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NONE"));
    }
    @Test void rawErrorsAreNotExposed() throws Exception {
        when(service.status(42)).thenThrow(new IllegalStateException("secret signed URL password"));
        mvc.perform(get("/api/account/deletion-status").principal(userAuthentication(42, NOW)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value("ACCOUNT_DELETION_UNAVAILABLE"));
    }
    @Test void existingAdminJwtFilterCannotAuthorizeDeletionForAnEndUser() throws Exception {
        var jwt = new com.nukleus.vrmeeting.security.JwtUtil();
        var filter = new com.nukleus.vrmeeting.security.JwtFilter();
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "jwtUtil", jwt);
        var contextFilter = new org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter();
        contextFilter.afterPropertiesSet();
        var withExistingFilter = MockMvcBuilders.standaloneSetup(new AccountDeletionController(service,
            new AuthenticatedAccountResolver(CLOCK, new AccountDeletionProperties())))
            .setControllerAdvice(new AccountDeletionExceptionHandler()).addFilters(filter, contextFilter).build();
        try {
            withExistingFilter.perform(post("/api/account/deletion-request")
                .servletPath("/api/account/deletion-request")
                .header("Authorization", "Bearer " + jwt.generateToken("synthetic@example.invalid"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_AUTHENTICATION_REQUIRED"));
            // Verify the principal produced by the unchanged existing filter.
            var authenticated = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
            org.junit.jupiter.api.Assertions.assertThrows(AccountDeletionException.class, () ->
                new AuthenticatedAccountResolver(CLOCK, new AccountDeletionProperties()).resolve(authenticated, true));
            verifyNoInteractions(service);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
}
