package com.myplus.education.service;

import com.myplus.education.entity.BehaviourNote;
import com.myplus.education.entity.NoteStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Slice 2.5 — the rules governing a behaviour note, as pure functions.
 *
 * <p>Same shape as {@code ClashDetector} (2.1), {@code FreeTeacherFinder} (2.2),
 * {@code LeaveBalanceCalculator} (2.3) and {@code HomeworkRules} (2.4): every input is an argument, so the
 * rules protecting the most sensitive record in the system test with no Spring, no DB and no Docker.
 */
public final class BehaviourNoteRules {

    private BehaviourNoteRules() { }

    /** A note must actually say something — a blank account is worse than no note at all. */
    public static String validate(String description, LocalDate occurredOn, LocalDate today) {
        if (description == null || description.isBlank()) {
            return "Describe what happened";
        }
        if (occurredOn != null && today != null && occurredOn.isAfter(today)) {
            // Recording a future incident is always a typo, and a wrong date on a contested record is
            // exactly the detail argued over later.
            return "The date is in the future";
        }
        return null;
    }

    /**
     * Whether a note may be superseded.
     *
     * <p>Only an ACTIVE note can be corrected. Superseding an already-superseded note would fork the trail:
     * two "corrections" of the same original, with nothing saying which is current. The correct move is to
     * supersede the note that is currently active.
     */
    public static String canSupersede(BehaviourNote note) {
        if (note == null) return "Note not found";
        if (note.getStatus() == NoteStatus.SUPERSEDED) {
            return "This note has already been corrected. Correct the current version instead.";
        }
        return null;
    }

    /**
     * The active notes, in the order given.
     *
     * <p>Used for counts and summaries. The full history keeps superseded rows — hiding them would
     * reproduce the problem immutability exists to prevent (D3) — but a count of "concerns this term"
     * must not double-count a note and its correction.
     */
    public static List<BehaviourNote> activeOnly(List<BehaviourNote> notes) {
        List<BehaviourNote> out = new ArrayList<>();
        for (BehaviourNote n : notes == null ? List.<BehaviourNote>of() : notes) {
            if (n != null && n.getStatus() != NoteStatus.SUPERSEDED) out.add(n);
        }
        return out;
    }

    /**
     * Whether the guardian-informed flag is coherent.
     *
     * <p>A date without the flag is a contradiction the screen should not be able to produce, and it is the
     * kind of inconsistency that undermines the record's credibility when it is read back.
     */
    public static String validateGuardianInformed(boolean informed, LocalDate informedOn) {
        if (!informed && informedOn != null) {
            return "A date is recorded for informing the guardian, but the box is not ticked";
        }
        return null;
    }
}
