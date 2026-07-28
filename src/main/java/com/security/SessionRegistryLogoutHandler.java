package com.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.logout.LogoutHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Drops the session from the {@link SessionRegistry} on logout.
 *
 * The registry normally learns a session is gone from an HttpSessionDestroyedEvent, but this app
 * logs out with {@code invalidateHttpSession(false)} — the session survives, so no such event fires
 * and the user would keep counting as "online" until the session eventually timed out. That left the
 * landing page's users-online figure stale for the whole session timeout after someone signed out.
 *
 * Best-effort, like {@link RevokeTokenLogoutHandler}: nothing here may block a logout.
 *
 * Registered as a bean in {@code SecSecurityConfig} rather than component-scanned — see the comment
 * on that bean method for why constructor-injecting the registry into a scanned component would
 * couple it back to the config class mid-construction.
 */
public class SessionRegistryLogoutHandler implements LogoutHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRegistryLogoutHandler.class);

    private final SessionRegistry sessionRegistry;

    public SessionRegistryLogoutHandler(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        try {
            final HttpSession session = request.getSession(false);
            if (session != null) {
                sessionRegistry.removeSessionInformation(session.getId());
            }
        } catch (Exception e) {
            LOGGER.warn("Could not remove session from the registry on logout (continuing): {}", e.getMessage());
        }
    }
}
