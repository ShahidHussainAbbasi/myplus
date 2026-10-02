package com.myplus.party.service;

import static com.myplus.common.web.PartyKeys.conflict;

import java.util.List;
import java.util.Optional;

import com.myplus.common.web.PartyKeys;
import com.myplus.party.entity.Party;

/**
 * DR-1 — which existing partner, if any, an incoming identity belongs to.
 *
 * <p>Order (strongest first):
 * <ol>
 *   <li><b>tax key</b> (CNIC / NTN) — a legal identity;</li>
 *   <li><b>phone key</b> — the number however it was typed, unless the candidate's tax key differs;</li>
 *   <li><b>raw contact</b> — only when the incoming contact is too short to have a phone key (the old behaviour,
 *       kept so nothing that matched before stops matching);</li>
 *   <li><b>email</b> — only when it cannot contradict a stronger key: never when both sides have phone keys that
 *       differ, never when both have tax keys that differ. An office address shared by two people used to merge
 *       them; this is the rule that stops it.</li>
 * </ol>
 * Never by name. Earliest candidate first, so a tenant that already holds two keeps matching the original.
 *
 * <p>Pure: the lookups are passed in, so the rules are unit-tested without a database.
 */
final class PartyMatcher {

    /** The queries the matcher may run — each is called only if the stages before it found nothing. */
    interface Lookup {
        List<Party> byTaxKey(String taxKey);
        List<Party> byContactKey(String contactKey);
        Optional<Party> byRawContact(String contact);
        List<Party> byEmail(String email);
    }

    private PartyMatcher() {}

    static Party match(String taxId, String contact, String email, Lookup lookup) {
        String tax = PartyKeys.taxKey(taxId);
        String phone = PartyKeys.phoneKey(contact);
        String mail = PartyKeys.emailKey(email);

        if (tax != null) {
            List<Party> hits = lookup.byTaxKey(tax);
            if (!hits.isEmpty()) return hits.get(0);
        }
        if (phone != null) {
            for (Party p : lookup.byContactKey(phone)) {
                if (!conflict(p.getTaxKey(), tax)) return p;
            }
        } else if (contact != null && !contact.isBlank()) {
            Party p = lookup.byRawContact(contact.trim()).orElse(null);
            if (p != null && !conflict(p.getTaxKey(), tax)) return p;
        }
        if (mail != null) {
            for (Party p : lookup.byEmail(email.trim())) {
                if (!conflict(p.getContactKey(), phone) && !conflict(p.getTaxKey(), tax)) return p;
            }
        }
        return null;
    }
}
