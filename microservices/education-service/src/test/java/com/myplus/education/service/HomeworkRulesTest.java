package com.myplus.education.service;

import com.myplus.education.entity.HomeworkSubmission;
import com.myplus.education.entity.SubmissionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 2.4 — homework rules.
 *
 * Pure: no Spring, no database, no Docker, so it runs on every {@code mvn test}.
 */
class HomeworkRulesTest {

    private static final LocalDate DUE = LocalDate.parse("2026-09-11");

    private static HomeworkSubmission sub(SubmissionState state) {
        return HomeworkSubmission.builder()
                .homeworkId(1L).studentEnrollNo("S1").state(state)
                .userId(1L).organizationId(1L).build();
    }

    // ── lateness is derived (D5) ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("submitting after the due date is late; on the day is NOT")
    void late_only_after_the_due_date() {
        assertTrue(HomeworkRules.isLate(LocalDate.parse("2026-09-12"), DUE));
        // A deadline is the last acceptable day, not the first unacceptable one.
        assertFalse(HomeworkRules.isLate(DUE, DUE));
        assertFalse(HomeworkRules.isLate(LocalDate.parse("2026-09-10"), DUE));
    }

    @Test
    @DisplayName("extending the deadline un-lates a submission — the reason late is not stored")
    void extending_the_deadline_unlates() {
        LocalDate submitted = LocalDate.parse("2026-09-12");
        assertTrue(HomeworkRules.isLate(submitted, DUE), "late against the original Friday");
        assertFalse(HomeworkRules.isLate(submitted, LocalDate.parse("2026-09-14")),
                "the same submission is on time once the deadline moves — a stored flag could not do this");
    }

    @Test
    @DisplayName("no due date or no submission date means nothing can be late")
    void late_needs_both_dates() {
        assertFalse(HomeworkRules.isLate(null, DUE));
        assertFalse(HomeworkRules.isLate(LocalDate.parse("2026-09-12"), null));
    }

    // ── overdue-unrecorded is an observation, NOT an accusation (D3) ────────────────────────────

    @Test
    @DisplayName("past the deadline with nothing recorded is flagged")
    void overdue_and_unrecorded() {
        assertTrue(HomeworkRules.isOverdueUnrecorded(null, DUE, LocalDate.parse("2026-09-12")));
    }

    @Test
    @DisplayName("anything recorded stops it being 'unrecorded' — including NOT_DONE")
    void recorded_is_not_overdue_unrecorded() {
        LocalDate after = LocalDate.parse("2026-09-12");
        assertFalse(HomeworkRules.isOverdueUnrecorded(SubmissionState.SUBMITTED, DUE, after));
        assertFalse(HomeworkRules.isOverdueUnrecorded(SubmissionState.MARKED, DUE, after));
        // The teacher has already judged this one; the calendar has nothing left to point out.
        assertFalse(HomeworkRules.isOverdueUnrecorded(SubmissionState.NOT_DONE, DUE, after));
    }

    @Test
    @DisplayName("before the deadline, an empty row is not overdue — the system must not accuse early")
    void not_overdue_before_the_deadline() {
        assertFalse(HomeworkRules.isOverdueUnrecorded(null, DUE, LocalDate.parse("2026-09-10")));
        assertFalse(HomeworkRules.isOverdueUnrecorded(null, DUE, DUE), "on the day is still in time");
    }

    @Test
    @DisplayName("homework with no due date is never overdue")
    void no_deadline_never_overdue() {
        assertFalse(HomeworkRules.isOverdueUnrecorded(null, null, LocalDate.parse("2026-12-31")));
    }

    // ── marks validation ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an ungraded submission is legitimate, not an error")
    void null_marks_are_fine() {
        assertNull(HomeworkRules.validateMarks(null, 20));
    }

    @Test
    @DisplayName("marks are refused when negative or above the ceiling, and the message names the figures")
    void marks_bounds() {
        assertNotNull(HomeworkRules.validateMarks(-1, 20));
        String tooHigh = HomeworkRules.validateMarks(21, 20);
        assertNotNull(tooHigh);
        assertTrue(tooHigh.contains("21") && tooHigh.contains("20"), tooHigh);
        assertNull(HomeworkRules.validateMarks(20, 20), "exactly full marks is valid");
        assertNull(HomeworkRules.validateMarks(0, 20), "a genuine zero is valid");
    }

    @Test
    @DisplayName("with no maximum set, any non-negative mark is accepted")
    void no_ceiling_no_upper_bound() {
        assertNull(HomeworkRules.validateMarks(999, null));
        assertNotNull(HomeworkRules.validateMarks(-1, null), "negative is still wrong");
    }

    // ── delete safety ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("homework with a MARKED submission cannot be deleted")
    void marked_work_blocks_delete() {
        // A grade is a teacher's judgement of a child's work; losing it to a mis-click is unrecoverable.
        assertFalse(HomeworkRules.canDelete(List.of(sub(SubmissionState.MARKED))));
        assertFalse(HomeworkRules.canDelete(
                List.of(sub(SubmissionState.SUBMITTED), sub(SubmissionState.MARKED))));
    }

    @Test
    @DisplayName("ungraded notes do not block a delete")
    void unmarked_rows_allow_delete() {
        assertTrue(HomeworkRules.canDelete(List.of()));
        assertTrue(HomeworkRules.canDelete(null));
        assertTrue(HomeworkRules.canDelete(
                List.of(sub(SubmissionState.SUBMITTED), sub(SubmissionState.NOT_DONE))),
                "a submission note is not a judgement");
    }

    @Test
    @DisplayName("the recorded count counts rows, which under lazy creation is exactly the point")
    void recorded_count() {
        assertEquals(0, HomeworkRules.recordedCount(List.of()));
        assertEquals(2, HomeworkRules.recordedCount(
                List.of(sub(SubmissionState.SUBMITTED), sub(SubmissionState.MARKED))));
    }
}
