package com.web.controller.business;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.PartyRestClient;

/**
 * Contact-360: proxy the cross-module contact view to party-service ({@code GET /parties/{id}/roles}) — one shared
 * identity + every module role it plays. Owner/admin-gated on BOTH sides: the mere existence of a pharmacy PATIENT
 * role is sensitive (a cashier must not learn a customer is a patient), so party-service enforces it and this proxy
 * gates too (defence in depth). Raw JSON pass-through ({party, roles[]}).
 */
@Controller
public class PartyController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private PartyRestClient party;

    /** The party's identity + roles across modules, by partyId. Owner/admin only. */
    @GetMapping(value = "/partyRoles", produces = "application/json")
    @ResponseBody
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    public String partyRoles(final HttpServletRequest request) {
        String id = request.getParameter("id");
        if (id == null || id.isBlank()) return "{}";
        try {
            return party.get("/parties/" + id.trim() + "/roles");
        } catch (Exception e) {
            // 404 (foreign/missing party) or a party-service hiccup — the screen shows "not available", not an error.
            LOGGER.warn("partyRoles proxy: no contact view for id {}", id);
            return "{}";
        }
    }

    /**
     * DR-1 — partners sharing a phone number or a CNIC / NTN (probably one business entered twice), with their roles.
     * Owner/admin only, for the same reason as {@link #partyRoles}. Read-only.
     */
    @GetMapping(value = "/partyDuplicates", produces = "application/json")
    @ResponseBody
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    public String partyDuplicates() {
        try {
            return party.get("/parties/duplicates");
        } catch (Exception e) {
            // The screen says "could not load" on a failure; an empty list would read as "no duplicates", which is a
            // claim this proxy cannot make when party-service did not answer.
            LOGGER.error("partyDuplicates proxy: party-service did not answer", e);
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, "Possible duplicates are not available right now.");
        }
    }

    @Autowired
    private com.web.util.BusinessRestClient business;

    /** DR-3 — the partner's position (they owe us / we owe them / net if set off). Read-only. Owner/admin, both sides. */
    @GetMapping(value = "/partyPosition", produces = "application/json")
    @ResponseBody
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    public java.util.Map<String, Object> partyPosition(final HttpServletRequest request) {
        String id = request.getParameter("partyId");
        try {
            // Only a number goes onward: the value is put into a query string, never passed through as typed.
            Long partyId = (id == null || id.isBlank()) ? null : Long.valueOf(id.trim());
            return business.get("/partyPosition", partyId == null ? null : "partyId=" + partyId);
        } catch (NumberFormatException bad) {
            return java.util.Map.of("status", "FAILED", "message", "Choose a partner.");
        } catch (Exception e) {
            return com.web.util.ProxyErrors.statusError(e);
        }
    }

    /** DR-5 — the partner's open balance on the other side, for the Receive Payment / Pay Vendor dialogs. Owner/admin. */
    @GetMapping(value = "/partyPaymentHint", produces = "application/json")
    @ResponseBody
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    public java.util.Map<String, Object> partyPaymentHint(final HttpServletRequest request) {
        try {
            // Only numbers go onward, and only one of the two.
            String c = request.getParameter("customerId"), v = request.getParameter("venderId");
            String q = (c != null && !c.isBlank()) ? "customerId=" + Long.valueOf(c.trim())
                     : (v != null && !v.isBlank()) ? "venderId=" + Long.valueOf(v.trim()) : null;
            if (q == null) return java.util.Map.of("status", "FAILED", "message", "Choose a customer or a supplier.");
            return business.get("/partyPaymentHint", q);
        } catch (NumberFormatException bad) {
            return java.util.Map.of("status", "FAILED", "message", "Choose a customer or a supplier.");
        } catch (Exception e) {
            return com.web.util.ProxyErrors.statusError(e);
        }
    }

    /** DR-4 — record a set-off (both legs, atomic, idempotent). Owner/admin, both sides. */
    @org.springframework.web.bind.annotation.PostMapping(value = "/partySetOff", produces = "application/json")
    @ResponseBody
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    public java.util.Map<String, Object> partySetOff(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body) {
        try {
            return business.postJson("/partySetOff", body);
        } catch (Exception e) {
            return com.web.util.ProxyErrors.statusError(e);
        }
    }

    /** DR-4 — reverse a set-off. Owner/admin, both sides. */
    @org.springframework.web.bind.annotation.PostMapping(value = "/partySetOffReverse", produces = "application/json")
    @ResponseBody
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    public java.util.Map<String, Object> partySetOffReverse(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body) {
        try {
            return business.postJson("/partySetOffReverse", body);
        } catch (Exception e) {
            return com.web.util.ProxyErrors.statusError(e);
        }
    }

    /** DR-4 — a partner's set-offs, newest first. Owner/admin. */
    @GetMapping(value = "/partySetOffs", produces = "application/json")
    @ResponseBody
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    public java.util.Map<String, Object> partySetOffs(final HttpServletRequest request) {
        String id = request.getParameter("partyId");
        try {
            Long partyId = (id == null || id.isBlank()) ? null : Long.valueOf(id.trim());
            return business.get("/partySetOffs", partyId == null ? null : "partyId=" + partyId);
        } catch (NumberFormatException bad) {
            return java.util.Map.of("status", "FAILED", "message", "Choose a partner.");
        } catch (Exception e) {
            return com.web.util.ProxyErrors.statusError(e);
        }
    }

    /** DR-2 — link a supplier to a customer's partner: {@code {customerId, venderId}}. Owner/admin, both sides. */
    @org.springframework.web.bind.annotation.PostMapping(value = "/partyLink", produces = "application/json")
    @ResponseBody
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    public java.util.Map<String, Object> partyLink(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body) {
        try {
            return business.postJson("/partyLink", body);
        } catch (Exception e) {
            return com.web.util.ProxyErrors.statusError(e);
        }
    }

    /** DR-2 — give one customer or supplier a partner of its own: {@code {role, id}}. Owner/admin, both sides. */
    @org.springframework.web.bind.annotation.PostMapping(value = "/partyUnlink", produces = "application/json")
    @ResponseBody
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    public java.util.Map<String, Object> partyUnlink(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body) {
        try {
            return business.postJson("/partyUnlink", body);
        } catch (Exception e) {
            return com.web.util.ProxyErrors.statusError(e);
        }
    }
}
