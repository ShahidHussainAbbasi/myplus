package com.myplus.clinical.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import com.myplus.common.web.exception.ValidationException;

/**
 * HMS S1 — the field rules of a registration, as pure functions (unit-tested without Spring). Every refusal is a
 * sentence the front desk can act on.
 */
public final class PatientRules {

    private PatientRules() {}

    public static String phone(String raw) {
        String p = PhoneNumbers.normalise(raw);
        if (p == null) {
            throw new ValidationException((raw == null || raw.isBlank())
                    ? "Enter the patient's mobile number."
                    : "\"" + raw.trim() + "\" is not a mobile number. Use 11 digits, e.g. 03001234567.");
        }
        return p;
    }

    /** The name as typed, or the phone when none was typed yet (only the phone is required). */
    public static String name(String raw, String phone) {
        String n = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (n.length() > 120) throw new ValidationException("The name is longer than 120 characters.");
        return n.isEmpty() ? phone : n;
    }

    /** 13 digits, stored as 00000-0000000-0; null when blank. */
    public static String cnic(String raw, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) throw new ValidationException("Enter the patient's CNIC — this clinic requires it.");
            return null;
        }
        String d = raw.replaceAll("[^0-9]", "");
        if (d.length() != 13 || !raw.trim().matches("[0-9\\-\\s]+")) {
            throw new ValidationException("A CNIC has 13 digits, e.g. 42201-1234567-1.");
        }
        return d.substring(0, 5) + "-" + d.substring(5, 12) + "-" + d.substring(12);
    }

    public static LocalDate dateOfBirth(String raw, LocalDate today) {
        if (raw == null || raw.isBlank()) return null;
        LocalDate d;
        try {
            d = LocalDate.parse(raw.trim());
        } catch (DateTimeParseException bad) {
            throw new ValidationException("The date of birth is not a date.");
        }
        if (d.isAfter(today)) throw new ValidationException("The date of birth is in the future.");
        if (d.isBefore(today.minusYears(130))) throw new ValidationException("The date of birth is more than 130 years ago.");
        return d;
    }

    public static String sex(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (!s.equals("M") && !s.equals("F") && !s.equals("O")) {
            throw new ValidationException("Sex is M, F or O.");
        }
        return s;
    }

    /** MRN-ISB-26-000123: clinic code (else the organisation number), two-digit year, six-digit running number. */
    public static String mrn(String code, Long org, LocalDate today, long seq) {
        String c = (code == null || code.isBlank()) ? String.valueOf(org) : code;
        return String.format(Locale.ROOT, "MRN-%s-%02d-%06d", c, today.getYear() % 100, seq);
    }
}
