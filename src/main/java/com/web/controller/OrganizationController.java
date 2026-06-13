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

    /** Organizations the current user belongs to: {status, collection:[{id,name,role,active}]}. */
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
            out.put("status", "SUCCESS");
        } catch (Exception e) {
            // e.g. 403 from auth-service when the user is not a member of the target org.
            out.put("status", "ERROR");
            out.put("message", e.getMessage());
        }
        return out;
    }
}
