package com.myplus.education.service;

import com.myplus.education.entity.PromotionOutcome;
import com.myplus.education.service.PromotionPolicy.Config;
import com.myplus.education.service.PromotionPolicy.Proposal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 1.6 — the promotion rule.
 *
 * Pure: no Spring, no database, no Docker, so it runs on every {@code mvn test}. This decides whether a
 * child repeats a year, which is the strongest possible argument for it being testable in isolation.
 */
class PromotionPolicyTest {

    private static PromotionPolicy autoPromote() {
        return new PromotionPolicy(new Config(false, 33));
    }

    private static PromotionPolicy requirePass(int minPercent) {
        return new PromotionPolicy(new Config(true, minPercent));
    }

    @Test
    @DisplayName("the DEFAULT rule promotes everyone, whatever the result")
    void auto_promote_is_the_default() {
        Proposal p = autoPromote().propose(List.of(12.0), false);
        assertEquals(PromotionOutcome.PROMOTED, p.outcome());
        assertFalse(p.undecided());
    }

    @Test
    @DisplayName("with a pass mark required, a year average below it is RETAINED and the figure is named")
    void below_the_pass_mark_is_retained() {
        Proposal p = requirePass(33).propose(List.of(30.0, 26.0), false);   // average 28
        assertEquals(PromotionOutcome.RETAINED, p.outcome());
        assertTrue(p.reason().contains("28"), "the actual average is named: " + p.reason());
        assertTrue(p.reason().contains("33"), "the threshold is named too: " + p.reason());
    }

    @Test
    @DisplayName("exactly at the pass mark is a PASS, not a failure")
    void the_boundary_passes() {
        assertEquals(PromotionOutcome.PROMOTED, requirePass(33).propose(List.of(33.0), false).outcome());
    }

    @Test
    @DisplayName("no issued report card is UNDECIDED — never silently promoted, never silently retained")
    void no_card_is_undecided() {
        Proposal p = requirePass(33).propose(List.of(), false);
        assertTrue(p.undecided());
        assertNull(p.outcome(), "undecided is a state of the PROPOSAL, not an outcome value");
        assertTrue(p.reason().toLowerCase().contains("no report card"), p.reason());

        // …and the same when the list is null rather than empty.
        assertTrue(requirePass(33).propose(null, false).undecided());
    }

    @Test
    @DisplayName("undecided applies under auto-promote too — a missing card is a gap, not a pass")
    void undecided_even_when_auto_promoting() {
        // The tempting shortcut is "requirePass is off, so promote regardless". That would hide a year
        // with no results behind a policy that was never asked about this student.
        assertTrue(autoPromote().propose(List.of(), false).undecided());
    }

    @Test
    @DisplayName("a card issued for a term with nothing marked contributes no percentage")
    void null_percentages_are_ignored() {
        Proposal p = requirePass(33).propose(Arrays.asList(40.0, null), false);
        assertEquals(PromotionOutcome.PROMOTED, p.outcome());
        assertTrue(p.reason().contains("40"), "the average is over the counted terms only: " + p.reason());
    }

    @Test
    @DisplayName("cards that are ALL unmarked are undecided, not an average of nothing")
    void all_null_percentages_are_undecided() {
        assertTrue(requirePass(33).propose(Arrays.asList(null, null), false).undecided());
    }

    @Test
    @DisplayName("no target class means GRADUATED, and that wins over the pass mark")
    void graduating_short_circuits() {
        // A leaver's final-year average must not turn their graduation into a retention with nowhere to go.
        Proposal p = requirePass(90).propose(List.of(41.0), true);
        assertEquals(PromotionOutcome.GRADUATED, p.outcome());
        assertFalse(p.undecided());
    }

    @Test
    @DisplayName("graduation is decided even with no report card at all")
    void graduating_without_a_card() {
        assertEquals(PromotionOutcome.GRADUATED, requirePass(33).propose(List.of(), true).outcome());
    }

    @Test
    @DisplayName("the average is rounded to one decimal before it is compared")
    void rounding_at_the_boundary() {
        // 32.95 → 33.0 (rounded) meets a 33% pass mark; comparing the raw value would retain the child.
        Proposal p = requirePass(33).propose(List.of(32.9, 33.0), false);   // average 32.95
        assertEquals(PromotionOutcome.PROMOTED, p.outcome(), p.reason());
    }
}
