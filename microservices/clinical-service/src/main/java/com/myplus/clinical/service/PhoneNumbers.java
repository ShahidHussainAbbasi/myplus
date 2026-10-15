package com.myplus.clinical.service;

import com.myplus.common.web.PartyKeys;

/**
 * HMS S1 (M-01b) — the patient's phone, which is the ONLY mandatory field and the patient's identity, so it is
 * validated strictly: a Pakistani mobile in any of the spellings people type.
 *
 * <pre>
 *   03001234567 · 0300-1234567 · +923001234567 · 923001234567 · 00923001234567   → accepted, shown as 03001234567
 *   12345 · 0300123 · 04235761234 (landline) · letters                         → refused
 * </pre>
 * The match key is {@link PartyKeys#phoneKey} — the same key party-service matches people on, so the patient and
 * the person it links to can never disagree about whether two numbers are one.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    /** The national form {@code 03XXXXXXXXX}, or null when this is not a Pakistani mobile number. */
    public static String normalise(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty() || !t.matches("[0-9+\\-\\s()]+")) return null;
        String d = t.replaceAll("[^0-9]", "");
        if (d.startsWith("0092")) d = d.substring(4);
        else if (d.startsWith("92") && d.length() == 12) d = d.substring(2);
        else if (d.startsWith("0") && d.length() == 11) d = d.substring(1);
        // what is left must be the 10-digit mobile number: 3 then nine digits
        if (d.length() != 10 || d.charAt(0) != '3') return null;
        return "0" + d;
    }

    /** The match key for a number that {@link #normalise} accepted. */
    public static String key(String normalised) {
        return PartyKeys.phoneKey(normalised);
    }
}
