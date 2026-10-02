package com.myplus.business_service.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.service.PartyRoleService;
import com.myplus.business_service.util.GenericResponse;

/**
 * DR-2 — the owner's manual link / unlink between a customer and a supplier (D4: owner and admin only).
 * A refusal answers 200 + FAILED with a sentence the screen shows as-is; a party-service failure answers ERROR.
 */
@RestController
public class PartyRoleController {

    private static final Logger LOG = LoggerFactory.getLogger(PartyRoleController.class);
    private static final String OWNER_OR_ADMIN =
            "hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')";

    @Autowired
    private PartyRoleService service;

    /** Body: {@code {customerId, venderId}} — the supplier joins the customer's partner. */
    @PreAuthorize(OWNER_OR_ADMIN)
    @PostMapping("/partyLink")
    public GenericResponse link(@RequestBody Map<String, Object> body) {
        try {
            boolean changed = service.link(asLong(body.get("customerId")), asLong(body.get("venderId")));
            return new GenericResponse("SUCCESS", changed ? "Linked: one partner, both roles." : "They were already one partner.");
        } catch (PartyRoleService.Refusal r) {
            return new GenericResponse("FAILED", r.getMessage());
        } catch (Exception e) {
            LOG.error("partyLink failed", e);
            return new GenericResponse("ERROR", "Could not link them right now. Nothing was changed here; try again.");
        }
    }

    /** Body: {@code {role: CUSTOMER|VENDOR, id}} — that record gets a partner of its own. */
    @PreAuthorize(OWNER_OR_ADMIN)
    @PostMapping("/partyUnlink")
    public GenericResponse unlink(@RequestBody Map<String, Object> body) {
        try {
            Long partyId = service.unlink(body.get("role") == null ? null : String.valueOf(body.get("role")), asLong(body.get("id")));
            return new GenericResponse("SUCCESS", "Unlinked: it now has a partner of its own.", partyId);
        } catch (PartyRoleService.Refusal r) {
            return new GenericResponse("FAILED", r.getMessage());
        } catch (Exception e) {
            LOG.error("partyUnlink failed", e);
            return new GenericResponse("ERROR", "Could not unlink it right now. Try again.");
        }
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }
}
