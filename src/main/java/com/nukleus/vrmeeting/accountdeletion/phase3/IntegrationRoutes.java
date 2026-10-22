package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.PendingDeletionAccessAdapter;
import com.nukleus.vrmeeting.accountdeletion.phase2.PendingDeletionAccessAdapter.Operation;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.IOException;
import java.util.Set;

/** Invoked inside the EXISTING JwtFilter, not independently registered as a second servlet filter. */
public final class IntegrationRoutes {
    private final Phase3Properties properties;
    private final AccountDeletionProperties deletion;
    private final JdbcSessionAuthority sessions;
    private final PendingDeletionAccessAdapter access;
    private final OperationFence fence;
    private final ObjectMapper json;
    public IntegrationRoutes(Phase3Properties properties, AccountDeletionProperties deletion,
                             JdbcSessionAuthority sessions, PendingDeletionAccessAdapter access,
                             OperationFence fence, ObjectMapper json) {
        this.properties = properties; this.deletion = deletion; this.sessions = sessions;
        this.access = access; this.fence = fence; this.json = json;
    }
    @FunctionalInterface public interface LegacyFilter {
        void run() throws IOException, ServletException;
    }
    public void route(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                      LegacyFilter legacy) throws IOException, ServletException {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        boolean account = path.equals("/api/account") || path.startsWith("/api/account/");
        try {
            if (!properties.isEnabled()) {
                if (account || path.equals("/api/auth/google") || path.equals("/api/auth/google-challenge"))
                    throw new AccountDeletionException(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_DELETION_DISABLED");
                legacy.run();
                return;
            }
            // No alternate encoded/path-parameter route can skip classification.
            String uri = request.getRequestURI();
            if (uri.contains("%") || uri.contains(";") || uri.contains("\\") || uri.contains("//"))
                throw new AccountDeletionException(HttpStatus.BAD_REQUEST, "INVALID_ROUTE");
            if (request.isAsyncStarted()) throw AccountDeletionException.integration();
            boolean exclusive = account && !"GET".equals(request.getMethod());
            try (var lease = fence.acquire(exclusive)) {
                if (path.equals("/api/auth/google-challenge")) {
                    chain.doFilter(request, response);
                    lease.assertLive();
                    return;
                }
                boolean legacyAuth = Set.of("/api/auth/login", "/api/auth/register", "/api/auth/google",
                    "/api/admin/login").contains(path) || path.startsWith("/api/admin/") || path.startsWith("/api/otp/");
                if (legacyAuth || !path.startsWith("/api/")) {
                    legacy.run(); // Admin JWT filter remains the authority for admin/OTP routes.
                } else {
                    if (account && !deletion.isApiEnabled())
                        throw new AccountDeletionException(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_DELETION_DISABLED");
                    var authentication = sessions.authenticate(request.getHeader("Authorization"));
                    Operation operation = switch (path) {
                        case "/api/account/deletion-request" -> Operation.REQUEST_DELETION;
                        case "/api/account/deletion-status" -> Operation.DELETION_STATUS;
                        case "/api/account/cancel-deletion" -> Operation.CANCEL_DELETION;
                        default -> Operation.NORMAL;
                    };
                    access.authorize(authentication, operation);
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authentication);
                    SecurityContextHolder.setContext(context);
                    try { chain.doFilter(request, response); }
                    finally { SecurityContextHolder.clearContext(); }
                }
                lease.assertLive();
                if (request.isAsyncStarted()) throw AccountDeletionException.integration();
            }
        } catch (AccountDeletionException e) {
            error(response, e.getStatus().value(), e.getCode());
        } catch (DataAccessException e) {
            error(response, 503, "ACCOUNT_INTEGRATION_UNAVAILABLE");
        }
    }
    private void error(HttpServletResponse response, int status, String code) throws IOException {
        if (response.isCommitted()) return;
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getWriter(), new AccountDeletionDtos.ErrorResponse(code, code));
    }
}

