package com.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

import com.persistence.model.User;

/**
 * Reports the currently logged-in users from the Spring Security {@link SessionRegistry}. No database:
 * the monolith authenticates against the auth-service and holds the {@link User} principal in-session only.
 */
@Service
public class UserService implements IUserService {

    @Autowired
    private SessionRegistry sessionRegistry;

    @Override
    public List<String> getUsersFromSessionRegistry() {
        return sessionRegistry.getAllPrincipals()
            .stream()
            .filter((u) -> !sessionRegistry.getAllSessions(u, false).isEmpty())
            .map(o -> (o instanceof User) ? ((User) o).getEmail() : o.toString())
            .collect(Collectors.toList());
    }

    /**
     * Counts principals rather than sessions: {@code maximumSessions(1)} in SecSecurityConfig means
     * one session per user anyway, and "users online" is the figure being reported.
     *
     * {@code getAllSessions(u, false)} excludes sessions already marked expired by concurrency control;
     * sessions destroyed by logout or timeout are pruned from the registry by the
     * {@code HttpSessionEventPublisher} registered in SecSecurityConfig. Without that publisher this
     * count would only ever climb.
     */
    @Override
    public int getLoggedInUserCount() {
        int live = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!sessionRegistry.getAllSessions(principal, false).isEmpty()) {
                live++;
            }
        }
        return live;
    }

}
