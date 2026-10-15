package com.myplus.clinical.service;

import java.util.Locale;
import java.util.Set;

import com.myplus.clinical.config.AppointmentDirectoryClient.Doctor;
import com.myplus.clinical.entity.ProviderDay;

/** HMS S2 — the queue's arithmetic, as pure functions (unit-tested without Spring). */
public final class QueueRules {

    private QueueRules() {}

    /**
     * The doctor's usual patients a day, or null for no limit — the same reading appointment-service gives its own
     * daily capacity ("count" = a number; minutes per patient = the hours divided by it; blank or 0 = unlimited).
     */
    public static Integer usualLimit(Doctor d) {
        if (d == null) return null;
        Integer v = d.getAppointmentOfferValue();
        if (v == null || v <= 0) return null;
        if ("count".equalsIgnoreCase(d.getAppointmentOfferType())) return v;
        try {
            int hours = Integer.parseInt(d.getTimeOut().split(":")[0]) - Integer.parseInt(d.getTimeIn().split(":")[0]);
            int slots = (hours * 60) / v;
            return slots > 0 ? slots : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** Today's limit: the one-day change when there is one (0 or less = no limit), else the usual. */
    public static Integer todayLimit(Integer usual, ProviderDay day) {
        if (day == null || day.getCap() == null) return usual;
        return day.getCap() <= 0 ? null : day.getCap();
    }

    /** A-007. */
    public static String label(String prefix, int tokenNo) {
        return String.format(Locale.ROOT, "%s-%03d", prefix, tokenNo);
    }

    /** The first free token letter: A … Z, then AA, AB … (a clinic with more than 26 doctors). */
    public static String nextPrefix(Set<String> taken) {
        for (int n = 0; n < 26 * 27; n++) {
            String p = n < 26 ? String.valueOf((char) ('A' + n))
                    : "" + (char) ('A' + (n / 26) - 1) + (char) ('A' + (n % 26));
            if (!taken.contains(p)) return p;
        }
        throw new IllegalStateException("No token letter is free.");
    }

    /** The counter for one doctor's day in org_document_seq (doc_type is 16 characters at most). */
    public static String counterKey(Long providerId, java.time.LocalDate day) {
        String k = "Q" + String.format(Locale.ROOT, "%02d%02d%02d", day.getYear() % 100, day.getMonthValue(), day.getDayOfMonth())
                + "-" + providerId;
        if (k.length() > 16) throw new IllegalStateException("Doctor id too long for the token counter: " + providerId);
        return k;
    }
}
