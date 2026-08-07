package com.myplus.appointment.service;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice SCHED-1 (B2) — slot arithmetic, with no Spring, no DB and no clock.
 *
 * <p><b>The first test this service has ever had.</b> It had no test dependency and no test sources, which
 * is a large part of why its booking defect (programme §9d) went unnoticed for as long as it did: there was
 * nowhere for a test to disagree with the code.
 *
 * <p>Caveat worth keeping, because 3.1b earned it the hard way: these cases prove the ARITHMETIC. What
 * makes double-booking impossible is the UNIQUE key in V4 — a constraint holds under concurrency and a
 * method call does not.
 */
class SlotConflictDetectorTest {

    private static LocalDateTime at(int h, int m) {
        return LocalDateTime.of(2026, 8, 7, h, m);
    }

    @Test
    @DisplayName("BACK-TO-BACK slots do NOT overlap — the case a parents' evening is entirely made of")
    void touching_slots_do_not_overlap() {
        // 18:00-18:10 and 18:10-18:20. An inclusive comparison would call every consecutive pair a clash
        // and refuse to generate a single usable evening.
        assertFalse(SlotConflictDetector.overlaps(at(18, 0), at(18, 10), at(18, 10), at(18, 20)));
        assertFalse(SlotConflictDetector.overlaps(at(18, 10), at(18, 20), at(18, 0), at(18, 10)),
                "and it is symmetric");
    }

    @Test
    @DisplayName("genuinely overlapping windows are caught, in every arrangement")
    void real_overlaps_are_detected() {
        assertTrue(SlotConflictDetector.overlaps(at(18, 0), at(18, 20), at(18, 10), at(18, 30)), "partial");
        assertTrue(SlotConflictDetector.overlaps(at(18, 0), at(18, 30), at(18, 10), at(18, 20)), "contained");
        assertTrue(SlotConflictDetector.overlaps(at(18, 10), at(18, 20), at(18, 0), at(18, 30)), "containing");
        assertTrue(SlotConflictDetector.overlaps(at(18, 0), at(18, 10), at(18, 0), at(18, 10)), "identical");
    }

    @Test
    @DisplayName("separated windows do not overlap")
    void separated_windows_do_not_overlap() {
        assertFalse(SlotConflictDetector.overlaps(at(18, 0), at(18, 10), at(19, 0), at(19, 10)));
    }

    @Test
    @DisplayName("nonsense input is never a permissive answer")
    void nonsense_is_not_an_overlap() {
        // A null bound or an inverted window must not resolve to "no conflict, go ahead" by accident —
        // it resolves to false because there is no window to conflict WITH, and the caller's own
        // validation is what refuses the input.
        assertFalse(SlotConflictDetector.overlaps(null, at(18, 10), at(18, 0), at(18, 10)));
        assertFalse(SlotConflictDetector.overlaps(at(18, 0), null, at(18, 0), at(18, 10)));
        assertFalse(SlotConflictDetector.overlaps(at(18, 10), at(18, 0), at(18, 0), at(18, 30)), "inverted");
        assertFalse(SlotConflictDetector.overlaps(at(18, 0), at(18, 0), at(18, 0), at(18, 30)), "zero-length");
    }

    @Test
    @DisplayName("an hour in 10-minute slots is SIX slots, and the last one ends exactly on the hour")
    void generate_cuts_a_window_cleanly() {
        List<SlotConflictDetector.Window> slots =
                SlotConflictDetector.generate(at(18, 0), at(19, 0), 10);
        assertEquals(6, slots.size());
        assertEquals(at(18, 0), slots.get(0).startsAt());
        assertEquals(at(18, 10), slots.get(0).endsAt());
        assertEquals(at(18, 50), slots.get(5).startsAt());
        assertEquals(at(19, 0), slots.get(5).endsAt(), "the last slot ends exactly when the evening does");
    }

    @Test
    @DisplayName("a slot that would run PAST the end is not generated — six-and-a-bit is six")
    void generate_drops_the_partial_tail() {
        // 18:00-18:55 in 10s: five slots, and the leftover five minutes is not a slot. A school that
        // published a 18:50-19:00 slot on a 18:55 finish would have a parent waiting in an empty corridor.
        List<SlotConflictDetector.Window> slots =
                SlotConflictDetector.generate(at(18, 0), at(18, 55), 10);
        assertEquals(5, slots.size());
        assertEquals(at(18, 50), slots.get(4).endsAt());
    }

    @Test
    @DisplayName("generated slots never overlap each other — the property, not just the count")
    void generated_slots_are_mutually_non_overlapping() {
        List<SlotConflictDetector.Window> slots = SlotConflictDetector.generate(at(9, 0), at(12, 0), 15);
        assertEquals(12, slots.size());
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                assertFalse(SlotConflictDetector.overlaps(
                                slots.get(i).startsAt(), slots.get(i).endsAt(),
                                slots.get(j).startsAt(), slots.get(j).endsAt()),
                        "slot " + i + " must not overlap slot " + j);
            }
        }
    }

    @Test
    @DisplayName("a form typo yields an empty grid, not an exception")
    void bad_generation_input_returns_empty() {
        // Generation is driven by a form. A typo should show nothing to correct, not a stack trace.
        assertTrue(SlotConflictDetector.generate(at(18, 0), at(19, 0), 0).isEmpty(), "zero length");
        assertTrue(SlotConflictDetector.generate(at(18, 0), at(19, 0), -10).isEmpty(), "negative");
        assertTrue(SlotConflictDetector.generate(at(19, 0), at(18, 0), 10).isEmpty(), "inverted window");
        assertTrue(SlotConflictDetector.generate(null, at(19, 0), 10).isEmpty(), "null bound");
        assertTrue(SlotConflictDetector.generate(at(18, 0), at(18, 5), 10).isEmpty(),
                "a window shorter than one slot yields none, never one that overruns");
    }
}
