package com.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.persistence.model.User;

@Component("myAuthenticationSuccessHandler")
public class MySimpleUrlAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Autowired
    ActiveUserStore activeUserStore;
    
    @Override
    public void onAuthenticationSuccess(final HttpServletRequest request, final HttpServletResponse response, final Authentication authentication) throws IOException {
        handle(request, response, authentication);
        final HttpSession session = request.getSession(false);
        if (session != null) {
            session.setMaxInactiveInterval(8 * 60 * 60);//h*m*s
            
            String username;
            if (authentication.getPrincipal() instanceof User) {
            	username = ((User)authentication.getPrincipal()).getEmail();
            }
            else {
            	username = authentication.getName();
            }
            LoggedUser user = new LoggedUser(username, activeUserStore);
            session.setAttribute("user", user);
        }
        clearAuthenticationAttributes(request);
    }

    protected void handle(final HttpServletRequest request, final HttpServletResponse response, final Authentication authentication) throws IOException {
        final String targetUrl = determineTargetUrl(authentication);

        if (response.isCommitted()) {
            logger.debug("Response has already been committed. Unable to redirect to " + targetUrl);
            return;
        }
        redirectStrategy.sendRedirect(request, response, targetUrl);
    }

//    protected String determineTargetUrl(final Authentication authentication) {
//        boolean isUser = false;
//        boolean isAdmin = false;
//        final Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
//        for (final GrantedAuthority grantedAuthority : authorities) {
//            if (grantedAuthority.getAuthority().equals("READ_PRIVILEGE")) {
//                isUser = true;
//            } else if (grantedAuthority.getAuthority().equals("WRITE_PRIVILEGE")) {
//                isAdmin = true;
//                isUser = false;
//                break;
//            }
//        }
//        if (isUser) {
//        	 String username;
//             if (authentication.getPrincipal() instanceof User) {
//             	username = ((User)authentication.getPrincipal()).getEmail();
//             }
//             else {
//             	username = authentication.getName();
//             }
//        
//            return "/homepage.html?user="+username;
//        } else if (isAdmin) {
//        	return "/homepage.html";
//            //return "/console.html";
//        } else {
//            throw new IllegalStateException();
//        }
//    }

    /**
     * Navigate the user to their dashboard.
     *
     * <p>B2B P0.5: the type&rarr;dashboard map moved to {@link com.web.util.ModuleRouter}, which is now the
     * single implementation shared with {@code AppController.dashboard()}. The two used to keep their own
     * copies and had already drifted (APPOINTMENT routed here but not there). Routing also now prefers the
     * ACTIVE ORG's module over the user's own type, so one login reaches every module it belongs to.
     */
    protected String determineTargetUrl(final Authentication authentication) {
        if (authentication.getPrincipal() instanceof User) {
            // Slice 3.3 — a PORTAL session is routed by its role, and that check comes first because the
            // role is the more specific fact. A guardian and a student are both EDUCATION users, so module
            // routing alone lands them on the staff dashboard: a page assembled entirely from reads the
            // deny rule then answers with 404. Non-portal sessions get null here and fall straight through.
            String portal = com.web.util.ModuleRouter.portalDashboardFor(authentication.getAuthorities());
            if (portal != null) {
                return portal;
            }
            return com.web.util.ModuleRouter.dashboardFor((User) authentication.getPrincipal());
        } else {
            throw new IllegalStateException();
        }
    }

    protected void clearAuthenticationAttributes(final HttpServletRequest request) {
        final HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        session.removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
    }

    public void setRedirectStrategy(final RedirectStrategy redirectStrategy) {
        this.redirectStrategy = redirectStrategy;
    }

    protected RedirectStrategy getRedirectStrategy() {
        return redirectStrategy;
    }
}