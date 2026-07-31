package com.myplus.education.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Shared validation primitives for education's forms.
 *
 * Extracted when slice B's §6 follow-on needed the same negative-money check that {@link FeeValidator}
 * already owned. The no-duplicate-functions rule applies to validation helpers as much as to anything
 * else: two copies of "is this negative" drift the moment one message is reworded.
 *
 * Every method APPENDS to a problem list rather than throwing, because every caller reports all of a
 * form's problems at once — one round trip per mistake is how a form earns its reputation.
 */
final class Validations {

    private Validations() { }

    /** Money and counts: negative is refused everywhere in this module. */
    static void negative(List<String> problems, String label, Integer value) {
        if (value != null && value < 0) {
            problems.add(label + " cannot be negative (" + value + ")");
        }
    }

    /**
     * A percentage that exceeds 100 discounts more than the thing it applies to. The damage is silent —
     * {@code monthlyDue} floors the result at 0 — so it must be refused at entry or nothing ever reports it.
     */
    static void percentOver100(List<String> problems, String label, Integer value) {
        if (value != null && value > 100) {
            problems.add(label + " " + value + "% cannot exceed 100%");
        }
    }

    /** An end before its start is nonsense no screen can render. */
    static void dateOrder(List<String> problems, String label, LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            problems.add(label + " ends (" + end + ") before it starts (" + start + ")");
        }
    }

    /** Same, for a class's daily window. */
    static void timeOrder(List<String> problems, String label, LocalTime from, LocalTime to) {
        if (from != null && to != null && to.isBefore(from)) {
            problems.add(label + " ends (" + to + ") before it starts (" + from + ")");
        }
    }
}
