package com.myplus.education.service;

import com.myplus.education.entity.PromotionOutcome;

import java.util.List;

/**
 * Slice 1.6 — the promotion rule, as a STRATEGY / policy object.
 *
 * <p>Pattern note (deliberate): the policy takes its configuration as a constructor argument instead of
 * reading {@code SettingsService} itself. Two consequences, both wanted:
 * <ul>
 *   <li>it is a pure function of its inputs, so every rule below tests without Spring, a database or
 *       Docker — and the maths that decides whether a child repeats a year is exactly the code that
 *       most deserves that;</li>
 *   <li>the settings are read ONCE per batch rather than once per student, which for a 40-child class is
 *       the difference between one lookup and forty.</li>
 * </ul>
 *
 * <p>The alternative — {@code if (org == …)} branching inside the service — is what the per-tenant
 * settings store exists to prevent.
 */
public final class PromotionPolicy {

    /** The org's configured rule, read once and handed in. */
    public record Config(boolean requirePass, int minPercent) { }

    /**
     * What the policy proposes for one student, and why.
     *
     * @param outcome null means UNDECIDED — see {@link #propose}. Not a value of
     *                {@link PromotionOutcome}, because undecided is a state of the PROPOSAL, never of the
     *                record: a student the school has not decided about has no promotion row at all.
     */
    public record Proposal(PromotionOutcome outcome, String reason, boolean undecided) {
        static Proposal of(PromotionOutcome o, String reason) { return new Proposal(o, reason, false); }
        static Proposal undecided(String reason) { return new Proposal(null, reason, true); }
    }

    private final Config config;

    public PromotionPolicy(Config config) {
        this.config = config;
    }

    /**
     * Propose an outcome from the student's ISSUED results for the year.
     *
     * <p><b>D2 — these percentages come from published report cards, never from live marks.</b> Re-deriving
     * would let a re-band of the grading scale in August change who was promoted in June.
     *
     * <p><b>An empty list is UNDECIDED, never a failure.</b> Silently retaining a student because no card
     * was issued would be a serious accusation made by a null check; silently promoting them would hide an
     * incomplete year. The school is asked.
     *
     * @param issuedPercents the term percentages from each PUBLISHED card for the year; may contain nulls
     *                       (a card can be issued for a term with nothing marked), which are ignored
     * @param graduating     true when no target class was chosen — the top of the school
     */
    public Proposal propose(List<Double> issuedPercents, boolean graduating) {
        if (graduating) {
            return Proposal.of(PromotionOutcome.GRADUATED, "Leaving the final class");
        }

        List<Double> counted = issuedPercents == null ? List.of()
                : issuedPercents.stream().filter(p -> p != null).toList();

        if (counted.isEmpty()) {
            return Proposal.undecided("No report card has been issued for this student this year");
        }

        if (!config.requirePass()) {
            // Auto-promote is the default: many jurisdictions run no-detention policies, and retention is
            // the consequential act — a default that never holds a child back by accident is the safe one.
            return Proposal.of(PromotionOutcome.PROMOTED, "All students are promoted (pass mark not required)");
        }

        double average = counted.stream().mapToDouble(Double::doubleValue).sum() / counted.size();
        double rounded = Math.round(average * 10.0) / 10.0;

        if (rounded >= config.minPercent()) {
            return Proposal.of(PromotionOutcome.PROMOTED,
                    "Year average " + rounded + "% meets the " + config.minPercent() + "% pass mark");
        }
        // The figure is named on both sides. A retention that says only "did not pass" is unreviewable.
        return Proposal.of(PromotionOutcome.RETAINED,
                "Year average " + rounded + "% is below the " + config.minPercent() + "% pass mark");
    }
}
