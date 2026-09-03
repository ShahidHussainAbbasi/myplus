package com.web.controller.platform;

import com.web.util.ProxyErrors;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * E2 — the operator portal's BFF: a thin proxy onto auth-service's {@code /api/auth/admin/**}.
 *
 * <h3>No rules of its own, deliberately</h3>
 * Backend-for-Frontend, exactly the shape {@code BusinessConfigController} already has for C3c. Every decision
 * — who may read the tenant list, whether a plan is valid, whether a reason was given — is made in
 * auth-service, on the authority that actually travels in the token. {@code GatewayClient} forwards the
 * operator's own access token, so the real gate is {@code @PreAuthorize("hasAuthority('ROLE_ADMIN')")} there.
 *
 * <p><b>What the {@code @PreAuthorize} below is worth, stated honestly:</b> it stops this proxy answering a
 * customer, which is a useful second line and is <b>not</b> the control. If it were deleted, auth-service
 * would still refuse — and the gate asserts the refusal, not the hiding.
 *
 * <h3>ROLE_ADMIN, never ADMIN_PRIVILEGE</h3>
 * Every tenant owner holds {@code ADMIN_PRIVILEGE} inside their own organization. Gating a platform surface on
 * it would hand every customer the list of every other customer. The same reasoning is already recorded on
 * {@code provision-tenant} and on E1's entitlement API; this is the first place it governs a SCREEN.
 */
@Controller
public class PlatformAdminController {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformAdminController.class);

    /** The gateway prefix auth-service is routed under. {@code /api/auth/**} has no StripPrefix. */
    private static final String AUTH_PREFIX = "/api/auth";

    @Autowired
    private com.web.util.GatewayClient gateway;

    @Value("${auth.server.url:http://localhost:8765}")
    private String authDirectUrl;

    /** One page of tenants. Search and paging are server-side — see OrganizationAdminService. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/organizations", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> organizations(final HttpServletRequest request) {
        try {
            String q = request.getParameter("q");
            String page = request.getParameter("page");
            String size = request.getParameter("size");
            StringBuilder qs = new StringBuilder("?page=").append(page == null ? "0" : enc(page))
                    .append("&size=").append(size == null ? "25" : enc(size));
            if (q != null && !q.isBlank()) qs.append("&q=").append(enc(q));
            // ONB-2 — the remediation worklist: tenants with no business type, or parked on `general`.
            if ("true".equals(request.getParameter("needsType"))) qs.append("&needsType=true");
            return authGet("/admin/organizations" + qs);
        } catch (Exception e) {
            LOGGER.error("platform organizations proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** One tenant's capabilities, with inPlan / grantable / revoked as the resolver computes them. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/entitlements", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> entitlements(final HttpServletRequest request) {
        try {
            String orgId = request.getParameter("organizationId");
            return authGet("/admin/entitlements?organizationId=" + enc(orgId));
        } catch (Exception e) {
            LOGGER.error("platform entitlements proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /**
     * Grant or revoke one capability.
     *
     * <p>Form-encoded in, JSON out to auth — the screen posts a form like every other monolith screen, and
     * auth's admin API takes a JSON body. The translation lives here rather than in the browser so the
     * operator page keeps the same posting idiom as the rest of the app.
     *
     * <p>{@code reason} is passed straight through and is <b>not</b> validated here: auth-service refuses a
     * blank one, and duplicating that check would create a second place for the rule to drift.
     */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/entitlement", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> setEntitlement(final HttpServletRequest request) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("organizationId", request.getParameter("organizationId"));
            body.put("capability", request.getParameter("capability"));
            body.put("status", request.getParameter("status"));
            body.put("reason", request.getParameter("reason"));
            String endsAt = request.getParameter("endsAt");
            if (endsAt != null && !endsAt.isBlank()) body.put("endsAt", endsAt);
            return authPost("/admin/entitlements", body);
        } catch (Exception e) {
            LOGGER.error("platform entitlement write proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** Change a tenant's plan. Validated against the Plan enum in auth-service, not here. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/plan", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> changePlan(final HttpServletRequest request) {
        try {
            String orgId = request.getParameter("organizationId");
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("plan", request.getParameter("plan"));
            body.put("reason", request.getParameter("reason"));
            return authPost("/admin/organizations/" + enc(orgId) + "/plan", body);
        } catch (Exception e) {
            LOGGER.error("platform plan proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /**
     * E3 — start or stop a tenant trading.
     *
     * <p>Nothing is validated here: auth-service refuses an unknown status, a missing reason and an operator's
     * attempt to suspend their own organization. Duplicating any of that would create a second place for the
     * rule to drift, and the second place is always the one that goes stale.
     */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/status", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> changeStatus(final HttpServletRequest request) {
        try {
            String orgId = request.getParameter("organizationId");
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("status", request.getParameter("status"));
            body.put("reason", request.getParameter("reason"));
            return authPost("/admin/organizations/" + enc(orgId) + "/status", body);
        } catch (Exception e) {
            LOGGER.error("platform status proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** ONB-1 — change a tenant's business type. Validated in auth-service, not here. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/shape", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> changeShape(final HttpServletRequest request) {
        try {
            String orgId = request.getParameter("organizationId");
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("shape", request.getParameter("shape"));
            body.put("reason", request.getParameter("reason"));
            return authPost("/admin/organizations/" + enc(orgId) + "/shape", body);
        } catch (Exception e) {
            LOGGER.error("platform shape proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** ONB-1 — what a shape change would turn on and off, so the console can name it before doing it. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/shapePreview", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> previewShape(final HttpServletRequest request) {
        try {
            String orgId = request.getParameter("organizationId");
            String shape = request.getParameter("shape");
            return authGet("/admin/organizations/" + enc(orgId) + "/shape-preview?shape=" + enc(shape));
        } catch (Exception e) {
            LOGGER.error("platform shape preview proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /**
     * Provision a new tenant — the endpoint that has existed since slice 32 with no UI.
     *
     * <p>No password is ever issued: {@code AuthService.provisionTenant} sends the owner a password-reset
     * email so no operator-known credential exists for a customer's account.
     */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/provisionTenant", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> provisionTenant(final HttpServletRequest request) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("email", request.getParameter("email"));
            body.put("firstName", request.getParameter("firstName"));
            body.put("lastName", request.getParameter("lastName"));
            body.put("phone", request.getParameter("phone"));
            body.put("organizationName", request.getParameter("organizationName"));
            body.put("userType", request.getParameter("userType"));
            body.put("plan", request.getParameter("plan"));
            // ONB-1 — mandatory. auth-service refuses a blank or unrecognised value.
            body.put("shape", request.getParameter("shape"));
            return authPost("/admin/provision-tenant", body);
        } catch (Exception e) {
            LOGGER.error("platform provisionTenant proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    private Map<String, Object> authGet(String path) {
        return gateway.forMap(AUTH_PREFIX, authDirectUrl, path, HttpMethod.GET, null, null);
    }

    private Map<String, Object> authPost(String path, Object body) {
        return gateway.forMap(AUTH_PREFIX, authDirectUrl, path, HttpMethod.POST, body,
                MediaType.APPLICATION_JSON);
    }

    private static String enc(String s) {
        return s == null ? "" : java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
