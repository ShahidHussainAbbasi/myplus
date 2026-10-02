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
}
