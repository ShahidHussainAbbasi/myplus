package com.web.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.web.util.ModuleRouter;
import com.web.util.RequestUtil;

@Controller
public class AppController {

    @Autowired
    private RequestUtil requestUtil;

    @GetMapping("/")
    public String landing() {
        return "maxtheservice_dashboard";
    }

    /**
     * Send the user to their module's dashboard.
     *
     * <p>B2B P0.5: delegates to {@link ModuleRouter} — the same implementation the post-login handler uses.
     * This switch used to be a second, hand-maintained copy of that map and had already drifted: it had no
     * {@code APPOINTMENT} case, so an appointment user landed on their dashboard at login and was bounced to
     * the landing page if they ever came through here. Routing now also prefers the ACTIVE ORG's module, so
     * this is the endpoint the org switcher returns to after changing tenant.
     */
    @GetMapping("/dashboard")
    public String dashboard() {
        try {
            // Slice 3.3 — portal role first, exactly as the post-login handler does. Both paths must agree:
            // this class and that handler having their own copy of the routing rule is the drift P0.5
            // extracted ModuleRouter to end, and a portal session sent to the staff shell here would land
            // on a page whose every read the deny rule answers with 404.
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            String portal = ModuleRouter.portalDashboardFor(auth == null ? null : auth.getAuthorities());
            if (portal != null) {
                return "redirect:" + portal;
            }
            return "redirect:" + ModuleRouter.dashboardFor(requestUtil.getCurrentUser());
        } catch (Exception e) {
            return "redirect:" + ModuleRouter.LANDING;
        }
    }
}
