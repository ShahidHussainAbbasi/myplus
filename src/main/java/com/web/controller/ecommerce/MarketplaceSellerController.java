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
            if (org == null || !String.valueOf(org).matches("\\d+")) return refusal("Choose the business to decide.");
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

    // ── MKT-1b: product proposals and match review ─────────────────────────────────────────────────────────

    /** Propose one of the seller's catalog products. Body: {sourceProductId, brand, model, variant, colour, …}. */
    @RequestMapping(value = "/mkt/proposeProduct", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> proposeProduct(@RequestBody final Map<String, Object> body) {
        try {
            return client.postJson("/mkt/products/propose", body);
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not send the product for review.");
        } catch (Exception e) {
            LOGGER.error("mkt proposeProduct proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    @RequestMapping(value = "/mkt/myProposals", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> myProposals(final HttpServletRequest request) {
        return relayGet("/mkt/products/proposals", request, "Could not load your products.", "page", "size");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/matchQueue", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> matchQueue(final HttpServletRequest request) {
        return relayGet("/mkt/operator/matches", request, "Could not load the review queue.", "status", "page", "size");
    }

    /** Body: {id, decision: MATCHED|NEEDS_CORRECTION|REJECTED, mktProductId, note, version}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/decideMatch", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> decideMatch(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body == null ? null : body.get("id");
            if (id == null || !String.valueOf(id).matches("\\d+")) return refusal("Choose the proposal to decide.");
            Map<String, Object> rest = new HashMap<>(body);
            rest.remove("id");
            return client.postJson("/mkt/operator/matches/" + id + "/decision", rest);
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not record the decision.");
        } catch (Exception e) {
            LOGGER.error("mkt decideMatch proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/products", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> canonicalProducts(final HttpServletRequest request) {
        return relayGet("/mkt/operator/products", request, "Could not load marketplace products.", "q", "page", "size");
    }

    // ── MKT-1c: offers and policies ────────────────────────────────────────────────────────────────────────

    /** Create (no id) or edit an offer. Party ids are stamped by the service; anything sent for them is ignored. */
    @RequestMapping(value = "/mkt/saveOffer", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> saveOffer(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/offers", body, "Could not save the offer.");
    }

    @RequestMapping(value = "/mkt/submitOffer", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> submitOffer(@RequestBody final Map<String, Object> body) {
        Object id = body == null ? null : body.get("id");
        if (id == null || !String.valueOf(id).matches("\\d+")) return refusal("Choose the offer to send.");
        return relayPost("/mkt/offers/" + id + "/submit", Map.of(), "Could not send the offer for approval.");
    }

    @RequestMapping(value = "/mkt/myOffers", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> myOffers(final HttpServletRequest request) {
        return relayGet("/mkt/offers", request, "Could not load your offers.", "page", "size");
    }

    @RequestMapping(value = "/mkt/getOffer", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getOffer(final HttpServletRequest request) {
        String id = request.getParameter("id");
        if (id == null || !id.matches("\\d+")) return refusal("Choose the offer.");
        return relayGet("/mkt/offers/" + id, request, "Could not load the offer.");
    }

    @RequestMapping(value = "/mkt/sellerPolicies", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> sellerPolicies(final HttpServletRequest request) {
        return relayGet("/mkt/policies", request, "Could not load the warranty and return policies.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/offerQueue", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> offerQueue(final HttpServletRequest request) {
        return relayGet("/mkt/operator/offers", request, "Could not load the offer queue.", "status", "page", "size");
    }

    /** Body: {id, decision: APPROVE|REJECT|SUSPEND|REINSTATE, note, version}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/decideOffer", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> decideOffer(@RequestBody final Map<String, Object> body) {
        Object id = body == null ? null : body.get("id");
        if (id == null || !String.valueOf(id).matches("\\d+")) return refusal("Choose the offer to decide.");
        Map<String, Object> rest = new HashMap<>(body);
        rest.remove("id");
        return relayPost("/mkt/operator/offers/" + id + "/decision", rest, "Could not record the decision.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/policies", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> policies(final HttpServletRequest request) {
        return relayGet("/mkt/operator/policies", request, "Could not load policies.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/createPolicy", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> createPolicy(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/policies", body, "Could not create the policy.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/deactivatePolicy", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> deactivatePolicy(@RequestBody final Map<String, Object> body) {
        Object id = body == null ? null : body.get("id");
        if (id == null || !String.valueOf(id).matches("\\d+")) return refusal("Choose the policy.");
        return relayPost("/mkt/operator/policies/" + id + "/deactivate", Map.of(), "Could not deactivate the policy.");
    }

    /** Body: {id (marketplace product), priceFloor, priceCeiling}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/productLimits", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> productLimits(@RequestBody final Map<String, Object> body) {
        Object id = body == null ? null : body.get("id");
        if (id == null || !String.valueOf(id).matches("\\d+")) return refusal("Choose the product.");
        Map<String, Object> rest = new HashMap<>(body);
        rest.remove("id");
        return relayPost("/mkt/operator/products/" + id + "/limits", rest, "Could not save the price limits.");
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> relayGet(String path, HttpServletRequest request, String fallback, String... params) {
        try {
            StringBuilder q = new StringBuilder(path).append("?x=1");
            for (String p : params) {
                String v = request.getParameter(p);
                if (v != null && !v.isBlank()) q.append('&').append(p).append('=').append(enc(v));
            }
            return client.get(q.toString());
        } catch (HttpStatusCodeException e) {
            return relayError(e, fallback);
        } catch (Exception e) {
            LOGGER.error("mkt proxy error " + path, e);
            return ProxyErrors.failure(e);
        }
    }

    private Map<String, Object> relayPost(String path, Object body, String fallback) {
        try {
            return client.postJson(path, body);
        } catch (HttpStatusCodeException e) {
            return relayError(e, fallback);
        } catch (Exception e) {
            LOGGER.error("mkt proxy error " + path, e);
            return ProxyErrors.failure(e);
        }
    }

    private static Map<String, Object> refusal(String message) {
        Map<String, Object> out = new HashMap<>();
        out.put("success", false);
        out.put("message", message);
        return out;
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
