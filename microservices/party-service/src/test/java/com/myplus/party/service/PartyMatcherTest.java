package com.myplus.party.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.myplus.common.web.PartyKeys;
import com.myplus.party.entity.Party;

/**
 * DR-1 — the match rules, against an in-memory "table". Each case is a way two records were wrongly split or
 * wrongly merged before DR-1.
 */
class PartyMatcherTest {

    private final List<Party> table = new ArrayList<>();

    private Party add(long id, String contact, String email, String taxId) {
        Party p = new Party();
        p.setId(id);
        p.setContact(contact);
        p.setContactKey(PartyKeys.phoneKey(contact));
        p.setEmail(email);
        p.setTaxKey(PartyKeys.taxKey(taxId));
        table.add(p);
        return p;
    }

    private final PartyMatcher.Lookup lookup = new PartyMatcher.Lookup() {
        public List<Party> byTaxKey(String k) { return table.stream().filter(p -> k.equals(p.getTaxKey())).toList(); }
        public List<Party> byContactKey(String k) { return table.stream().filter(p -> k.equals(p.getContactKey())).toList(); }
        public Optional<Party> byRawContact(String c) { return table.stream().filter(p -> c.equals(p.getContact())).findFirst(); }
        public List<Party> byEmail(String e) { return table.stream().filter(p -> e.equalsIgnoreCase(String.valueOf(p.getEmail()))).toList(); }
    };

    @Test
    void theSameNumberTypedAnotherWayIsTheSamePartner() {
        Party usman = add(1, "0300-1234567", null, null);
        assertEquals(usman, PartyMatcher.match(null, "+923001234567", null, lookup));
        assertEquals(usman, PartyMatcher.match(null, "03001234567", null, lookup));
    }

    @Test
    void aSharedOfficeEmailDoesNotMergeTwoPeopleWithDifferentPhones() {
        add(1, "03001234567", "office@abc.pk", null);
        assertNull(PartyMatcher.match(null, "03119876543", "office@abc.pk", lookup));
    }

    @Test
    void emailStillJoinsWhenOneSideHasNoPhone() {
        Party p = add(1, null, "office@abc.pk", null);
        assertEquals(p, PartyMatcher.match(null, "03119876543", "Office@ABC.pk", lookup));
    }

    @Test
    void theTaxIdOutranksADifferentPhone() {
        Party p = add(1, "03001234567", null, "35201-1234567-8");
        assertEquals(p, PartyMatcher.match("3520112345678", "03119876543", null, lookup));
    }

    @Test
    void aMatchingPhoneWithADifferentTaxIdIsADifferentPartner() {
        add(1, "03001234567", null, "35201-1234567-8");
        assertNull(PartyMatcher.match("42101-7654321-1", "0300-1234567", null, lookup));
    }

    @Test
    void theEarliestPartnerWinsWhenTheTenantAlreadyHoldsTwo() {
        Party first = add(1, "0300-1234567", null, null);
        add(2, "+923001234567", null, null);
        assertEquals(first, PartyMatcher.match(null, "03001234567", null, lookup));
    }

    @Test
    void aShortNumberStillMatchesItsExactText() {
        Party p = add(1, "4567", null, null);
        assertEquals(p, PartyMatcher.match(null, "4567", null, lookup));
    }

    @Test
    void nameIsNeverAKey() {
        add(1, "03001234567", null, null);
        assertNull(PartyMatcher.match(null, null, null, lookup));
    }
}
