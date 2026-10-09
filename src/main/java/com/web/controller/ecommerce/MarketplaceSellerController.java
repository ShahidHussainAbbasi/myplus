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

    /** MKT-1d: the order customers see first on a product page. GET → {sort}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/defaultSort", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> defaultSort(final HttpServletRequest request) {
        return relayGet("/mkt/operator/settings/default-sort", request, "Could not load the default order.");
    }

    /** Body: {sort: RECOMMENDED|LOWEST_PRICE|FASTEST|WARRANTY|RETURN_POLICY}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/defaultSort", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> setDefaultSort(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/settings/default-sort", body, "Could not save the default order.");
    }

    // ── MKT-1e: marketplace orders ──────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/mkt/incomingOrders", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> incomingOrders(final HttpServletRequest request) {
        return relayGet("/mkt/seller-orders", request, "Could not load your marketplace orders.", "status", "page", "size");
    }

    /** Body: {id, version, serials: {lineId: [imei, …]}}. */
    @RequestMapping(value = "/mkt/acceptOrder", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> acceptOrder(@RequestBody final Map<String, Object> body) {
        Object id = body == null ? null : body.get("id");
        if (id == null || !String.valueOf(id).matches("\\d+")) return refusal("Choose the order.");
        Map<String, Object> rest = new HashMap<>(body);
        rest.remove("id");
        return relayPost("/mkt/seller-orders/" + id + "/accept", rest, "Could not accept the order.");
    }

    /** Body: {id, version, reason}. */
    @RequestMapping(value = "/mkt/rejectOrder", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> rejectOrder(@RequestBody final Map<String, Object> body) {
        Object id = body == null ? null : body.get("id");
        if (id == null || !String.valueOf(id).matches("\\d+")) return refusal("Choose the order.");
        Map<String, Object> rest = new HashMap<>(body);
        rest.remove("id");
        return relayPost("/mkt/seller-orders/" + id + "/reject", rest, "Could not reject the order.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/orders", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> operatorOrders(final HttpServletRequest request) {
        return relayGet("/mkt/operator/orders", request, "Could not load marketplace orders.", "status", "page", "size");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/acceptWindow", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> acceptWindow(final HttpServletRequest request) {
        return relayGet("/mkt/operator/settings/accept-window", request, "Could not load the acceptance window.");
    }

    /** Body: {minutes: 1–60} and/or {multiSeller: true|false} (MKT-2a); a field not sent is left as it is. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/acceptWindow", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> setAcceptWindow(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/settings/accept-window", body, "Could not save the acceptance window.");
    }

    // ── MKT-2-06: the acceptance window by the part's value ─────────────────────────────────────────────

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/acceptTiers", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> acceptTiers(final HttpServletRequest request) {
        return relayGet("/mkt/operator/settings/accept-tiers", request, "Could not load the acceptance rules.");
    }

    /** Body: {tiers: [{above, minutes}]}; replaces the rules, an empty list removes them. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/acceptTiers", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> setAcceptTiers(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/settings/accept-tiers", body, "Could not save the acceptance rules.");
    }

    // ── MKT-3a: the MaxTheService warehouse ─────────────────────────────────────────────────────────────

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/warehouse", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> warehouse(final HttpServletRequest request) {
        return relayGet("/mkt/operator/warehouse", request, "Could not load the warehouse.");
    }

    /** Body: {organizationId}; null removes the warehouse. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/warehouse", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> setWarehouse(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/warehouse", body, "Could not save the warehouse.");
    }

    // ── MKT-2c: live routing — the limits, the sellers not being asked right now, the test switch ─────────

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/routing", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> routing(final HttpServletRequest request) {
        return relayGet("/mkt/operator/routing", request, "Could not load how sellers are asked for stock.");
    }

    /** Body: {sellerOrganizationId}. Its next checkout asks it again. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/routingClose", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> routingClose(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/routing/close", body, "Could not change the seller.");
    }

    /** Body: {sellerOrganizationId, delayMs}; refused by the service unless it was started with the test switch. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/routingTest", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> routingTest(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/routing/test", body, "Could not save the test switch.");
    }

    // ── MKT-1f: support cases and returns ───────────────────────────────────────────────────────────────

    /** A seller's tasks from MaxTheService: its own cases waiting on it and approved returns to collect. */
    @RequestMapping(value = "/mkt/sellerTasks", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> sellerTasks(final HttpServletRequest request) {
        return relayGet("/mkt/seller/tasks", request, "Could not load your tasks from MaxTheService.");
    }

    /** Body: {caseNo, body}. */
    @RequestMapping(value = "/mkt/sellerTaskReply", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> sellerTaskReply(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/seller/tasks/reply", body, "Could not send your answer.");
    }

    /** Body: {returnNo, outcome: RESTOCK|QUARANTINE|WRITE_OFF, cashHandedBack}. */
    @RequestMapping(value = "/mkt/returnReceived", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> returnReceived(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/seller/returns/received", body, "Could not record the returned item.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/cases", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> supportCases(final HttpServletRequest request) {
        return relayGet("/mkt/operator/cases", request, "Could not load the support cases.", "status", "page", "size");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/caseView", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> supportCase(final HttpServletRequest request) {
        String no = request.getParameter("caseNo");
        if (no == null || !no.trim().matches("(?i)SC-\\d{1,12}")) return refusal("Choose the case.");
        return relayGet("/mkt/operator/cases/" + enc(no.trim().toUpperCase()), request, "Could not load the case.");
    }

    /** Body: {caseNo, body, internal}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/caseReply", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> caseReply(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/cases/reply", body, "Could not send the reply.");
    }

    /** Body: {caseNo, note}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/caseTask", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> caseTask(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/cases/task", body, "Could not task the seller.");
    }

    /** Body: {caseNo, note}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/caseResolve", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> caseResolve(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/cases/resolve", body, "Could not resolve the case.");
    }

    /** Body: {returnNo, decision: APPROVED|REJECTED, note}. */
    // ── MKT-2b: a part a seller did not fulfil — the seller disputes the recorded cause, the operator decides ──

    /** Body: {id, note}. The seller's own record only; another seller's id reads as missing (checked by the service). */
    @RequestMapping(value = "/mkt/shortageDispute", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> shortageDispute(@RequestBody final Map<String, Object> body) {
        Object id = body == null ? null : body.get("id");
        if (id == null || !String.valueOf(id).matches("\\d+")) return refusal("Choose the record.");
        return relayPost("/mkt/seller/shortages/" + id + "/dispute", Map.of("note", String.valueOf(body.getOrDefault("note", ""))),
                "Could not send the dispute.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/shortages", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> shortages(final HttpServletRequest request) {
        return relayGet("/mkt/operator/shortages", request, "Could not load the shortages.", "status", "page", "size");
    }

    /** Body: {id, outcome: UPHELD|OVERTURNED, note}. Neither outcome moves money (R12.4). */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/shortageDecide", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> shortageDecide(@RequestBody final Map<String, Object> body) {
        Object id = body == null ? null : body.get("id");
        if (id == null || !String.valueOf(id).matches("\\d+")) return refusal("Choose the record.");
        Map<String, Object> rest = new HashMap<>(body);
        rest.remove("id");
        return relayPost("/mkt/operator/shortages/" + id + "/decide", rest, "Could not record the decision.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/returnDecision", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> returnDecision(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/returns/decision", body, "Could not record the decision.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/settings/changeOfMindFee", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> changeOfMindFee(final HttpServletRequest request) {
        return relayGet("/mkt/operator/settings/change-of-mind-fee", request, "Could not load the fee.");
    }

    /** Body: {amount}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/settings/changeOfMindFee", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> setChangeOfMindFee(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/settings/change-of-mind-fee", body, "Could not save the fee.");
    }

    // ── MKT-1g: settlement statement, ledger and payouts ───────────────────────────────────────────────────

    /** The seller's statement: its lines, each with the whole split. ?status=ELIGIBLE|PAID|… */
    @RequestMapping(value = "/mkt/statement", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> statement(final HttpServletRequest request) {
        return relayGet("/mkt/settlement/statement", request, "Could not load your settlement statement.", "status", "page", "size");
    }

    /** The seller's account: balance, ledger rows, payouts. */
    @RequestMapping(value = "/mkt/settlementAccount", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> settlementAccount(final HttpServletRequest request) {
        return relayGet("/mkt/settlement/account", request, "Could not load your settlement account.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/settlementAccounts", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> settlementAccounts(final HttpServletRequest request) {
        return relayGet("/mkt/operator/settlement/accounts", request, "Could not load the sellers' balances.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/settlementAccount", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> sellerSettlementAccount(final HttpServletRequest request) {
        String org = request.getParameter("organizationId");
        if (org == null || !org.trim().matches("\\d{1,18}")) return refusal("Choose the seller.");
        return relayGet("/mkt/operator/settlement/accounts/" + org.trim(), request, "Could not load the seller's account.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/runSettlement", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> runSettlement() {
        return relayPost("/mkt/operator/settlement/run", Map.of(), "Could not run the settlement.");
    }

    /** Body: {organizationId, amount (signed), reason, idempotencyKey}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/adjustLedger", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> adjustLedger(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/settlement/adjust", body, "Could not record the correction.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/settlementSettings", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> settlementSettings(final HttpServletRequest request) {
        return relayGet("/mkt/operator/settlement/settings", request, "Could not load the settlement settings.");
    }

    // ── MKT-2e: seller performance ─────────────────────────────────────────────────────────────────────

    /** The seller's own scorecard. ?days=7|30|90 */
    @RequestMapping(value = "/mkt/myPerformance", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> myPerformance(final HttpServletRequest request) {
        return relayGet("/mkt/seller/performance", request, "Could not load your performance.", "days");
    }

    /** Every seller's scorecard, flagged first. ?days=7|30|90 */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/sellerPerformance", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> sellerPerformance(final HttpServletRequest request) {
        return relayGet("/mkt/operator/performance", request, "Could not load the sellers' performance.", "days");
    }

    // ── MKT-2f: settlement reports and bank holidays ───────────────────────────────────────────────────

    /** The seller's own report row. ?from=&to= (ISO dates; this month by default) */
    @RequestMapping(value = "/mkt/settlementReport", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> mySettlementReport(final HttpServletRequest request) {
        return relayGet("/mkt/settlement/report", request, "Could not load your settlement report.", "from", "to");
    }

    /** Every seller's ledger over the period, column by column. ?from=&to= */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/settlementReport", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> settlementReport(final HttpServletRequest request) {
        return relayGet("/mkt/operator/settlement/report", request, "Could not load the settlement report.", "from", "to");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/holidays", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> holidays(final HttpServletRequest request) {
        return relayGet("/mkt/operator/settlement/holidays", request, "Could not load the bank holidays.");
    }

    /** Body: {date, name}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/addHoliday", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addHoliday(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/settlement/holidays", body, "Could not add the holiday.");
    }

    /** Body: {date}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/removeHoliday", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> removeHoliday(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/settlement/holidays/remove", body, "Could not remove the holiday.");
    }

    /** MKT-2d — cash orders: what each seller collected, paid and owes, and since when. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/codReconciliation", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> codReconciliation(final HttpServletRequest request) {
        return relayGet("/mkt/operator/settlement/cod", request, "Could not load what sellers owe for cash orders.");
    }

    /** MKT-2d — body: {organizationId, amount, reference, note, idempotencyKey}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/recordRemittance", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> recordRemittance(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/settlement/remittance", body, "Could not record the seller's payment.");
    }

    /** Body: {tPlusDays, useMyBooks, codRemitDays, codStopWhenOverdue}; a field not sent is left as it is. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/settlementSettings", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> saveSettlementSettings(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/settlement/settings", body, "Could not save the settlement settings.");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/payouts", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> payouts(final HttpServletRequest request) {
        return relayGet("/mkt/operator/payouts", request, "Could not load the payouts.", "status", "page", "size");
    }

    /** Body: {organizationId, idempotencyKey}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/requestPayout", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> requestPayout(@RequestBody final Map<String, Object> body) {
        return relayPost("/mkt/operator/payouts", body, "Could not request the payout.");
    }

    /** Body: {id}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/approvePayout", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> approvePayout(@RequestBody final Map<String, Object> body) {
        Object id = body == null ? null : body.get("id");
        if (id == null || !String.valueOf(id).matches("\\d+")) return refusal("Choose the payout.");
        return relayPost("/mkt/operator/payouts/" + id + "/approve", Map.of(), "Could not approve the payout.");
    }

    /** Body: {id, bankReference}. */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @RequestMapping(value = "/platform/mkt/markPayoutPaid", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> markPayoutPaid(@RequestBody final Map<String, Object> body) {
        Object id = body == null ? null : body.get("id");
        if (id == null || !String.valueOf(id).matches("\\d+")) return refusal("Choose the payout.");
        Map<String, Object> rest = new HashMap<>(body);
        rest.remove("id");
        return relayPost("/mkt/operator/payouts/" + id + "/mark-paid", rest, "Could not record the payment.");
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
