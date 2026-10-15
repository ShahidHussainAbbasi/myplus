package com.myplus.business_service.service;

import org.springframework.stereotype.Service;

import com.myplus.business_service.entity.SalesRep;
import com.myplus.common.web.exception.ValidationException;

/**
 * SRA-3 — WHO is credited with a sale, and how it came in. The one rule, used by every writer of an invoice (the till,
 * an order invoiced at dispatch, an edit). Design: {@code microservices/docs/slices/sra-sales-by-rep-and-area.md} §6.
 *
 * <pre>
 *   the booker IS a rep (their login is linked to one)          → that rep          · REP_LOGIN
 *        … unless they may manage reps and chose another rep   → the chosen rep    · REP_PAPER
 *   otherwise a rep was chosen (the office keying a paper order) → the chosen rep  · REP_PAPER
 *   otherwise                                                    → no rep          · COUNTER (till) · PORTAL (online)
 *                                                                                    · OFFICE (an order with no rep)
 * </pre>
 *
 * <p>A chosen rep must be one of THIS business's (anti-IDOR) — a foreign or unknown id is refused, not ignored: a sale
 * credited to nobody when the office meant somebody is a commission error nobody would see. An INACTIVE rep is still
 * accepted here — an order booked before the rep left is still theirs.
 *
 * <p>The rep's NAME is returned with the id, to be STAMPED on the invoice: a sale keeps the name it was made under.
 */
@Service
public class SalesRepAttribution {

    public static final String COUNTER = "COUNTER";
    public static final String REP_LOGIN = "REP_LOGIN";
    public static final String REP_PAPER = "REP_PAPER";
    public static final String OFFICE = "OFFICE";
    public static final String PORTAL = "PORTAL";

    private final SalesRepService reps;

    public SalesRepAttribution(SalesRepService reps) {
        this.reps = reps;
    }

    /** The decision: the rep (or none) and the source. */
    public record Credit(Long repId, String repName, String source) {}

    /**
     * @param orgId            the business the sale belongs to
     * @param bookerUserId     the login that took the order (the signed-in cashier on the till; the order's booker)
     * @param chosenRepId      the rep the office chose (null or 0 = none)
     * @param bookerMayManage  the booker holds rep.manage (may credit a rep other than themselves)
     * @param channel          null / POS = the till; FIELD = a booked order; STOREFRONT / MARKETPLACE = online
     */
    public Credit decide(Long orgId, Long bookerUserId, Long chosenRepId, boolean bookerMayManage, String channel) {
        Long chosen = (chosenRepId == null || chosenRepId == 0L) ? null : chosenRepId;
        SalesRep self = reps.repForUser(orgId, bookerUserId).orElse(null);
        if (self != null && !(chosen != null && bookerMayManage && !chosen.equals(self.getId()))) {
            return new Credit(self.getId(), self.getName(), REP_LOGIN);
        }
        if (chosen != null) {
            SalesRep r = reps.inOrg(chosen, orgId)
                    .orElseThrow(() -> new ValidationException("That sales rep was not found. Choose the rep again."));
            return new Credit(r.getId(), r.getName(), REP_PAPER);
        }
        return new Credit(null, null, fallbackSource(channel));
    }

    static String fallbackSource(String channel) {
        if (channel == null || channel.isBlank() || "POS".equalsIgnoreCase(channel)) return COUNTER;
        if ("FIELD".equalsIgnoreCase(channel)) return OFFICE;
        return PORTAL;   // STOREFRONT, MARKETPLACE, any other online channel
    }
}
