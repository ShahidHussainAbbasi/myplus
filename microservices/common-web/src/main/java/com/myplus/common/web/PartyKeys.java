package com.myplus.common.web;

import java.util.Locale;

/**
 * DR-1 — the match keys that decide whether two records are the same business partner.
 *
 * <p>ONE copy, used by party-service (to match) and by every module that sends it identity (to know what will
 * match). Before this, each place compared the raw text: {@code 0300-1234567}, {@code 03001234567} and
 * {@code +923001234567} — all accepted by the mobile validator — became three different partners.
 *
 * <p>Order of strength, strongest first (the order {@code PartyService.upsert} matches in):
 * <ol>
 *   <li>{@link #taxKey} — CNIC / NTN. A legal identity; two partners never share one.</li>
 *   <li>{@link #phoneKey} — the number, whatever way it was typed.</li>
 *   <li>{@link #emailKey} — weakest: an office address is often shared by different people, so it may only
 *       join two records when at least one of them has no phone key.</li>
 * </ol>
 * A name is never a key — "ABC Distributors" and "ABC Distributor Pvt Ltd" may or may not be one business.
 */
public final class PartyKeys {

    private PartyKeys() {}

    /** Digits a phone key keeps. A Pakistani mobile is 10 digits after the 0 / 92 / 0092 prefix. */
    public static final int PHONE_DIGITS = 10;

    /**
     * Digits only, keeping the last {@value #PHONE_DIGITS} — so every accepted spelling of one number is one key.
     * Null when fewer digits than that are present: a short or partial number is not worth matching on.
     */
    public static String phoneKey(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < PHONE_DIGITS) return null;
        return digits.substring(digits.length() - PHONE_DIGITS);
    }

    /**
     * CNIC / NTN digits only — {@code 35201-1234567-8} and {@code 3520112345678} are one person. Null when there
     * are fewer than 7 digits (an NTN is 7; anything shorter is a fragment, not an identity).
     */
    public static String taxKey(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() < 7 ? null : digits;
    }

    /** Trimmed and lower-cased; null when blank. */
    public static String emailKey(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        return t.isEmpty() ? null : t.toLowerCase(Locale.ROOT);
    }

    /** True when the two keys cannot belong to one partner: both present and different. A missing key never conflicts. */
    public static boolean conflict(String a, String b) {
        return a != null && b != null && !a.equals(b);
    }
}
