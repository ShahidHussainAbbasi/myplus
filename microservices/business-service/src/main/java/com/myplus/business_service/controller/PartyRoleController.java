package com.myplus.business_service.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    @Autowired
    private com.myplus.business_service.service.PartySetOffService setOffs;

    /** DR-3 — the partner's position: they owe us, we owe them, the net if set off. Read-only; owner/admin. */
    @PreAuthorize(OWNER_OR_ADMIN)
    @GetMapping("/partyPosition")
    public GenericResponse position(@RequestParam(required = false) Long partyId) {
        try {
            return new GenericResponse("SUCCESS", "Partner position", service.position(partyId));
        } catch (PartyRoleService.Refusal r) {
            return new GenericResponse("FAILED", r.getMessage());
        } catch (Exception e) {
            LOG.error("partyPosition failed", e);
            return new GenericResponse("ERROR", "Could not load the position right now.");
        }
    }

    /** DR-5 — {@code ?customerId=} (receiving) or {@code ?venderId=} (paying): the partner's open balance on the other side. */
    @PreAuthorize(OWNER_OR_ADMIN)
    @GetMapping("/partyPaymentHint")
    public GenericResponse paymentHint(@RequestParam(required = false) Long customerId, @RequestParam(required = false) Long venderId) {
        try {
            var h = customerId != null ? service.paymentHint("CUSTOMER", customerId) : service.paymentHint("VENDOR", venderId);
            return new GenericResponse("SUCCESS", "Payment hint", h);
        } catch (PartyRoleService.Refusal r) {
            return new GenericResponse("FAILED", r.getMessage());
        } catch (Exception e) {
            LOG.error("partyPaymentHint failed", e);
            return new GenericResponse("ERROR", "Could not check the other side.");
        }
    }

    // ---- DR-4 set-off ----------------------------------------------------------------------------------------------

    /** Body: {@code {customerId, venderId, amount, reason, reference, sameBusiness, idempotencyKey}}. */
    @PreAuthorize(OWNER_OR_ADMIN)
    @PostMapping("/partySetOff")
    public GenericResponse setOff(@RequestBody Map<String, Object> body) {
        try {
            var o = setOffs.setOff(asLong(body.get("customerId")), asLong(body.get("venderId")), asMoney(body.get("amount")),
                    str(body.get("reason")), str(body.get("reference")), Boolean.parseBoolean(String.valueOf(body.get("sameBusiness"))),
                    str(body.get("idempotencyKey")));
            return new GenericResponse("SUCCESS", (o.replay() ? "Already recorded: " : "Recorded: ") + o.setOffNo(), o);
        } catch (PartyRoleService.Refusal | com.myplus.business_service.service.PeriodClosedException r) {
            return new GenericResponse("FAILED", r.getMessage());
        } catch (Exception e) {
            // finance did not confirm (or anything else): the transaction rolled back, so nothing moved anywhere.
            LOG.error("partySetOff failed", e);
            return new GenericResponse("ERROR", "The set-off was not recorded and no balance changed. Try again in a moment.");
        }
    }

    /** Body: {@code {setOffId, reason, idempotencyKey}} — the key dedups the REVERSAL itself. */
    @PreAuthorize(OWNER_OR_ADMIN)
    @PostMapping("/partySetOffReverse")
    public GenericResponse reverse(@RequestBody Map<String, Object> body) {
        try {
            var o = setOffs.reverse(asLong(body.get("setOffId")), str(body.get("reason")), str(body.get("idempotencyKey")));
            return new GenericResponse("SUCCESS", "Reversed: " + o.setOffNo(), o);
        } catch (PartyRoleService.Refusal | com.myplus.business_service.service.PeriodClosedException r) {
            return new GenericResponse("FAILED", r.getMessage());
        } catch (Exception e) {
            LOG.error("partySetOffReverse failed", e);
            return new GenericResponse("ERROR", "The reversal was not recorded and no balance changed. Try again in a moment.");
        }
    }

    /** The partner's set-offs, newest first. */
    @PreAuthorize(OWNER_OR_ADMIN)
    @GetMapping("/partySetOffs")
    public GenericResponse list(@RequestParam(required = false) Long partyId) {
        try {
            return new GenericResponse("SUCCESS", "Set-offs", null, setOffs.forParty(partyId));
        } catch (Exception e) {
            LOG.error("partySetOffs failed", e);
            return new GenericResponse("ERROR", "Could not load the set-offs.");
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static java.math.BigDecimal asMoney(Object o) {
        if (o == null) return null;
        try { return new java.math.BigDecimal(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }

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
