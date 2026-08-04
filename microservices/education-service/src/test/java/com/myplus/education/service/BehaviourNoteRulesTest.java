package com.myplus.education.service;

import com.myplus.education.entity.BehaviourNote;
import com.myplus.education.entity.BehaviourType;
import com.myplus.education.entity.NoteStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 2.5 — behaviour note rules.
 *
 * Pure: no Spring, no database, no Docker, so it runs on every {@code mvn test}. These rules protect the
 * most sensitive record in the system, which is the strongest possible reason to test them in isolation.
 */
class BehaviourNoteRulesTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-09-15");

    private static BehaviourNote note(NoteStatus status) {
        return BehaviourNote.builder()
                .studentEnrollNo("S1").type(BehaviourType.CONCERN)
                .description("something happened").status(status)
                .userId(1L).organizationId(1L).build();
    }

    // ── validation ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a note must actually say something")
    void description_required() {
        // A blank account is worse than no note: it leaves a mark on a child's record saying nothing.
        assertNotNull(BehaviourNoteRules.validate(null, TODAY, TODAY));
        assertNotNull(BehaviourNoteRules.validate("", TODAY, TODAY));
        assertNotNull(BehaviourNoteRules.validate("   ", TODAY, TODAY));
        assertNull(BehaviourNoteRules.validate("disrupted the lesson", TODAY, TODAY));
    }

    @Test
    @DisplayName("a future date is refused — always a typo, and dates get argued over")
    void future_date_refused() {
        assertNotNull(BehaviourNoteRules.validate("x", LocalDate.parse("2026-09-16"), TODAY));
        assertNull(BehaviourNoteRules.validate("x", TODAY, TODAY), "today is fine");
        assertNull(BehaviourNoteRules.validate("x", LocalDate.parse("2026-09-01"), TODAY),
                "backdating is legitimate — incidents get written up later");
    }

    @Test
    @DisplayName("a missing date is not an error — the entity defaults it")
    void null_date_is_tolerated() {
        assertNull(BehaviourNoteRules.validate("x", null, TODAY));
    }

    // ── supersede ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an ACTIVE note can be corrected")
    void active_can_be_superseded() {
        assertNull(BehaviourNoteRules.canSupersede(note(NoteStatus.ACTIVE)));
    }

    @Test
    @DisplayName("an already-superseded note cannot be corrected again — that would fork the trail")
    void superseded_cannot_be_superseded() {
        // Two "corrections" of one original, with nothing saying which is current, is worse than a wrong
        // note: the reader cannot tell what the school actually maintains.
        String problem = BehaviourNoteRules.canSupersede(note(NoteStatus.SUPERSEDED));
        assertNotNull(problem);
        assertTrue(problem.toLowerCase().contains("already"), problem);
    }

    @Test
    @DisplayName("a missing note is reported, not NPE'd")
    void null_note_is_handled() {
        assertNotNull(BehaviourNoteRules.canSupersede(null));
    }

    // ── active filtering ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("counts use ACTIVE notes only, so a correction is not double-counted")
    void active_only_excludes_superseded() {
        List<BehaviourNote> all = List.of(
                note(NoteStatus.SUPERSEDED),   // the original
                note(NoteStatus.ACTIVE));      // its correction
        assertEquals(1, BehaviourNoteRules.activeOnly(all).size(),
                "one incident, recorded twice, must count once");
    }

    @Test
    @DisplayName("the full history is NOT filtered — activeOnly is for counts, not for display")
    void active_only_is_a_summary_helper() {
        // Hiding superseded rows from the history would reproduce exactly what immutability prevents.
        List<BehaviourNote> all = List.of(note(NoteStatus.SUPERSEDED), note(NoteStatus.ACTIVE));
        assertEquals(2, all.size(), "the caller keeps the full list for display");
        assertEquals(1, BehaviourNoteRules.activeOnly(all).size());
    }

    @Test
    @DisplayName("null and empty lists are safe")
    void active_only_edges() {
        assertTrue(BehaviourNoteRules.activeOnly(null).isEmpty());
        assertTrue(BehaviourNoteRules.activeOnly(List.of()).isEmpty());
        assertTrue(BehaviourNoteRules.activeOnly(Arrays.asList((BehaviourNote) null)).isEmpty());
    }

    // ── guardian-informed coherence ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a date without the tick is refused as incoherent")
    void guardian_informed_coherence() {
        assertNotNull(BehaviourNoteRules.validateGuardianInformed(false, TODAY));
        assertNull(BehaviourNoteRules.validateGuardianInformed(true, TODAY));
        assertNull(BehaviourNoteRules.validateGuardianInformed(false, null));
        assertNull(BehaviourNoteRules.validateGuardianInformed(true, null),
                "ticked without a date is allowed — the school may not remember exactly when");
    }
}
