package com.web.controller.ecommerce;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.util.MarketplaceRestClient;
import com.web.util.ProxyErrors;

/**
 * MKT-0a — monolith proxies for multi-seller marketplace onboarding → marketplace-service {@code /mkt/**}.
 * Design: microservices/docs/marketplace-multiseller-design.md · slice: docs/slices/mkt-0a-seller-onboarding.md
 *
 * <h3>No rules here</h3>
 * Capability, tier, agreement version, operator role and the account lifecycle are all decided in
 * marketplace-service. The {@code @PreAuthorize} on the operator routes stops this proxy answering a customer,
 * which is a second line and not the control: the service refuses a non-operator on its own.
 *
 * <h3>Shipped WITH its screens</h3>
 * {@code #MarketplaceDiv} on the business dashboard and the operator's Marketplace sellers panel call these. A
 * proxy with no screen is the inert-capability shape this codebase has recorded seven times.
 *
 * <h3>Errors are relayed, never flattened (D3d)</h3>
 * "Only the owner or an admin can accept…", "MaxTheService is still reviewing your seller account", "Give the
 * seller a reason" — each tells the person what to do. "Could not save" would send them to click again.
 */
@Controller
public class MarketplaceSellerController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketplaceSellerController.class);

    @Autowired
    private MarketplaceRestClient client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The caller's own seller status: capability, agreements, account, canSell. */
    @RequestMapping(value = "/mkt/seller", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> seller() {
        try {
            return client.get("/mkt/seller");
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not load your marketplace seller status.");
        } catch (Exception e) {
            LOGGER.error("mkt seller proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** Accept both agreements and apply. Body: {version, displayName}. */
    @RequestMapping(value = "/mkt/acceptAgreement", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> acceptAgreement(@RequestBody final Map<String, Object> body) {
        try {
            return client.postJson("/mkt/seller/agreements", body);
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not record your acceptance.");
        } catch (Exception e) {
            LOGGER.error("mkt acceptAgreement proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** The operator's seller queue. ?status=PENDING_APPROVAL|APPROVED|REJECTED|SUSPENDED&page=&size= */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/sellers", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> sellers(final HttpServletRequest request) {
        try {
            StringBuilder q = new StringBuilder("/mkt/operator/sellers?x=1");
            for (String p : new String[] {"status", "page", "size"}) {
                String v = request.getParameter(p);
                if (v != null && !v.isBlank()) q.append('&').append(p).append('=').append(enc(v));
            }
            return client.get(q.toString());
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not load marketplace sellers.");
        } catch (Exception e) {
            LOGGER.error("mkt sellers proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** Body: {organizationId, decision: APPROVE|REJECT|SUSPEND|REINSTATE, reason, version}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/decideSeller", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> decideSeller(@RequestBody final Map<String, Object> body) {
        try {
            Object org = body == null ? null : body.get("organizationId");
            if (org == null || !String.valueOf(org).matches("\\d+")) {
                Map<String, Object> out = new HashMap<>();
                out.put("success", false);
                out.put("message", "Choose the business to decide.");
                return out;
            }
            Map<String, Object> rest = new HashMap<>(body);
            rest.remove("organizationId");
            return client.postJson("/mkt/operator/sellers/" + org + "/decision", rest);
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not record the decision.");
        } catch (Exception e) {
            LOGGER.error("mkt decideSeller proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> relayError(HttpStatusCodeException e, String fallback) {
        Map<String, Object> out = new HashMap<>();
        out.put("success", false);
        out.put("statusCode", e.getStatusCode().value());
        try {
            Map<String, Object> err = objectMapper.readValue(e.getResponseBodyAsString(), Map.class);
            out.put("message", err.get("message") != null ? err.get("message") : fallback);
        } catch (Exception ignore) {
            out.put("message", fallback);
        }
        return out;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v.trim(), StandardCharsets.UTF_8);
    }
}
