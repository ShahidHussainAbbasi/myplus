package com.web.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.security.TokenStore;
import com.web.dto.AuthServerLoginResponse;
import com.web.util.AuthServerClient;

/**
 * Active-organization listing + switching for the logged-in session. Same-origin endpoints the
 * dashboards' JS calls. The JWT is held server-side in {@link TokenStore}; switching re-issues it
 * (auth-service validates membership) and we swap the session token so every later gateway call is
 * scoped to the new tenant. The browser only ever sees org names, never the tokens.
 */
@Controller
public class OrganizationController {

    @Autowired
    private AuthServerClient authServerClient;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private com.web.util.RequestUtil requestUtil;

    /** Organizations the current user belongs to: {status, collection:[{id,name,role,active,type}]}. */
    @RequestMapping(value = "/getMyOrganizations", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getMyOrganizations() {
        Map<String, Object> out = new HashMap<>();
        try {
            if (!tokenStore.hasAccessToken()) {
                // Legacy mode (auth.mode=local) — no JWT, so no tenant context to show.
                out.put("status", "NOT_FOUND");
                return out;
            }
            List<Map<String, Object>> orgs = authServerClient.organizations(tokenStore.getAccessToken());
            out.put("status", (orgs == null || orgs.isEmpty()) ? "NOT_FOUND" : "SUCCESS");
            out.put("collection", orgs);
        } catch (Exception e) {
            out.put("status", "ERROR");
            out.put("message", e.getMessage());
        }
        return out;
    }

    /** Switch the active organization, swapping the session token on success. */
    @RequestMapping(value = "/switchOrganization", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> switchOrganization(@RequestParam("organizationId") Long organizationId) {
        Map<String, Object> out = new HashMap<>();
        try {
            if (!tokenStore.hasAccessToken()) {
                out.put("status", "FAILED");
                out.put("message", "No active session token");
                return out;
            }
            AuthServerLoginResponse res = authServerClient.switchOrganization(
                    tokenStore.getAccessToken(), organizationId);
            if (res == null || res.getAccessToken() == null) {
                out.put("status", "FAILED");
                return out;
            }
            tokenStore.setAccessToken(res.getAccessToken());
            if (res.getRefreshToken() != null) {
                tokenStore.setRefreshToken(res.getRefreshToken());
            }
            // B2B P0.5 (R8): the principal is built from the LOGIN response and is not rebuilt on a switch,
            // so without this it keeps describing the previous tenant — and ModuleRouter would send the user
            // straight back to the module they just switched away from. Mutating the principal held by the
            // SecurityContext is the narrowest fix: no re-authentication, and the session keeps its identity
            // and authorities (which are unchanged — privileges are per user, not per org).
            com.persistence.model.User principal = requestUtil.getCurrentUser();
            if (principal != null) {
                principal.setActiveOrgType(res.getActiveOrgType());
            }
            out.put("status", "SUCCESS");
            // The client uses this to decide whether the switch changed module (and so whether the target
            // dashboard changes); it always redirects through /dashboard, which re-decides server-side.
            out.put("activeOrgType", res.getActiveOrgType());
        } catch (Exception e) {
            // e.g. 403 from auth-service when the user is not a member of the target org.
            out.put("status", "ERROR");
            out.put("message", e.getMessage());
        }
        return out;
    }
}
