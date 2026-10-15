package com.myplus.clinical.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.myplus.common.web.exception.ValidationException;

/**
 * HMS S3a — the vital signs a person can have, as pure functions (unit-tested). A value outside the range is a typo
 * ("986" for 98.6), refused with the range in the sentence, never stored. Blank = not measured.
 */
public final class EncounterRules {

    private EncounterRules() {}

    /** "120/80" → {120, 80}; null when blank. */
    public static int[] bloodPressure(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim().replace(" ", "");
        if (!t.matches("\\d{2,3}/\\d{2,3}")) {
            throw new ValidationException("Blood pressure is written as systolic/diastolic, e.g. 120/80.");
        }
        int sys = Integer.parseInt(t.split("/")[0]);
        int dia = Integer.parseInt(t.split("/")[1]);
        if (sys < 50 || sys > 260) throw new ValidationException("Systolic blood pressure must be between 50 and 260.");
        if (dia < 30 || dia > 160) throw new ValidationException("Diastolic blood pressure must be between 30 and 160.");
        if (dia >= sys) throw new ValidationException("The second number of a blood pressure is the lower one, e.g. 120/80.");
        return new int[] {sys, dia};
    }

    public static Integer whole(String raw, String what, int min, int max) {
        if (raw == null || raw.isBlank()) return null;
        int v;
        try {
            v = new BigDecimal(raw.trim()).setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (RuntimeException notANumber) {
            throw new ValidationException(what + " is not a number.");
        }
        if (v < min || v > max) throw new ValidationException(what + " must be between " + min + " and " + max + ".");
        return v;
    }

    public static BigDecimal decimal(String raw, String what, String min, String max) {
        if (raw == null || raw.isBlank()) return null;
        BigDecimal v;
        try {
            v = new BigDecimal(raw.trim()).setScale(1, RoundingMode.HALF_UP);
        } catch (RuntimeException notANumber) {
            throw new ValidationException(what + " is not a number.");
        }
        if (v.compareTo(new BigDecimal(min)) < 0 || v.compareTo(new BigDecimal(max)) > 0) {
            throw new ValidationException(what + " must be between " + min + " and " + max + ".");
        }
        return v;
    }

    public static Integer pulse(String raw) { return whole(raw, "Pulse", 20, 250); }

    public static Integer spo2(String raw) { return whole(raw, "SpO2", 50, 100); }

    /** °F, as Pakistani clinics record it. */
    public static BigDecimal temperatureF(String raw) { return decimal(raw, "Temperature (°F)", "90", "110"); }

    public static BigDecimal weightKg(String raw) { return decimal(raw, "Weight (kg)", "0.5", "300"); }

    public static BigDecimal heightCm(String raw) { return decimal(raw, "Height (cm)", "30", "250"); }

    public static String complaint(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        if (t.length() > 500) throw new ValidationException("The complaint is longer than 500 characters.");
        return t;
    }

    /** HMS S3b-1 — a prescription: 1..30 lines, each a catalogue medicine with a whole quantity 1..10000. */
    public static final int MAX_RX_LINES = 30;

    public static java.util.List<com.myplus.clinical.dto.ConsultDtos.RxLine> rxLines(
            java.util.List<com.myplus.clinical.dto.ConsultDtos.RxLine> raw) {
        java.util.List<com.myplus.clinical.dto.ConsultDtos.RxLine> out = new java.util.ArrayList<>();
        if (raw == null) return out;
        if (raw.size() > MAX_RX_LINES) throw new ValidationException("A prescription has at most " + MAX_RX_LINES + " medicines.");
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (com.myplus.clinical.dto.ConsultDtos.RxLine l : raw) {
            if (l == null) continue;
            String name = l.getMedicineName() == null ? "" : l.getMedicineName().trim();
            String which = name.isEmpty() ? "A medicine" : "'" + name + "'";
            if (l.getProductId() == null) throw new ValidationException(which + " is not from the pharmacy's list. Choose it again.");
            if (name.isEmpty() || name.length() > 200) throw new ValidationException("Choose the medicine again; its name is missing or too long.");
            if (!seen.add(l.getProductId())) throw new ValidationException(which + " is on the prescription twice. Change the quantity instead.");
            Integer q;
            try { q = Integer.valueOf(l.getQuantity() == null ? "" : l.getQuantity().trim()); }
            catch (NumberFormatException e) { q = null; }
            if (q == null || q < 1 || q > 10000) throw new ValidationException(which + " needs a quantity between 1 and 10000.");
            out.add(com.myplus.clinical.dto.ConsultDtos.RxLine.builder().productId(l.getProductId()).medicineName(name)
                    .quantity(String.valueOf(q)).dosage(text(l.getDosage(), which, "dose"))
                    .frequency(text(l.getFrequency(), which, "frequency")).duration(text(l.getDuration(), which, "duration")).build());
        }
        return out;
    }

    private static String text(String raw, String which, String what) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        if (t.length() > 100) throw new ValidationException("The " + what + " of " + which + " is longer than 100 characters.");
        return t;
    }

    public static String note(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) throw new ValidationException("Write the note first.");
        if (t.length() > 4000) throw new ValidationException("A note is at most 4000 characters; add a second note for the rest.");
        return t;
    }
}
