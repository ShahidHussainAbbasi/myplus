package com.myplus.education.service;

import com.myplus.education.entity.TimetableEntry;
import com.myplus.education.service.ClashDetector.Context;
import com.myplus.education.service.ClashDetector.Problem;
import com.myplus.education.service.ClashDetector.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 2.1 — clash detection.
 *
 * Pure: no Spring, no database, no Docker, so it runs on every {@code mvn test}. These rules decide
 * where a teacher physically stands, so they are tested against hand-built grids rather than by
 * asserting whatever the code happens to produce.
 */
class ClashDetectorTest {

    private static final long GRADE_5A = 51L, GRADE_6B = 62L;
    private static final long MATHS_5A = 501L, ENGLISH_5A = 502L;
    private static final long MRS_KHAN = 9L, MR_ALI = 10L;
    private static final long PERIOD_3 = 3L;

    private static TimetableEntry entry(Long id, Long gradeId, Long subjectId, Long staffId, String room) {
        return TimetableEntry.builder()
                .id(id).termId(1L).dayOfWeek(DayOfWeek.MONDAY).periodId(PERIOD_3)
                .gradeId(gradeId).subjectId(subjectId).staffId(staffId).room(room)
                .userId(1L).organizationId(1L)
                .build();
    }

    /** A context that asserts nothing extra — the grade matches and no windows are recorded. */
    private static Context ctxFor(Long subjectGradeId) {
        return new Context(subjectGradeId, null, null, null, null, null, null, null, null, null);
    }

    private static boolean has(List<Problem> ps, String field, Severity sev) {
        return ps.stream().anyMatch(p -> p.field().equals(field) && p.severity() == sev);
    }

    @Test
    @DisplayName("a free slot is accepted")
    void free_slot() {
        List<Problem> ps = ClashDetector.check(
                entry(null, GRADE_5A, MATHS_5A, MRS_KHAN, "12"), List.of(), ctxFor(GRADE_5A));
        assertTrue(ps.isEmpty(), () -> "expected no problems, got " + ps);
        assertFalse(ClashDetector.refuses(ps));
    }

    @Test
    @DisplayName("the same teacher in the same slot is REFUSED")
    void teacher_double_booked() {
        TimetableEntry existing = entry(1L, GRADE_6B, 601L, MRS_KHAN, "20");
        List<Problem> ps = ClashDetector.check(
                entry(null, GRADE_5A, MATHS_5A, MRS_KHAN, "12"), List.of(existing), ctxFor(GRADE_5A));
        assertTrue(has(ps, "staffId", Severity.REFUSE), () -> ps.toString());
        assertTrue(ClashDetector.refuses(ps));
    }

    @Test
    @DisplayName("the same class in the same slot is REFUSED, even with a different subject and teacher")
    void class_double_booked() {
        TimetableEntry existing = entry(1L, GRADE_5A, ENGLISH_5A, MR_ALI, "12");
        List<Problem> ps = ClashDetector.check(
                entry(null, GRADE_5A, MATHS_5A, MRS_KHAN, "13"), List.of(existing), ctxFor(GRADE_5A));
        assertTrue(has(ps, "periodId", Severity.REFUSE), () -> ps.toString());
    }

    @Test
    @DisplayName("a shared room WARNS but does not block — the room data is too weak to refuse on")
    void room_clash_only_warns() {
        TimetableEntry existing = entry(1L, GRADE_6B, 601L, MR_ALI, "Hall");
        List<Problem> ps = ClashDetector.check(
                entry(null, GRADE_5A, MATHS_5A, MRS_KHAN, "hall"), List.of(existing), ctxFor(GRADE_5A));
        assertTrue(has(ps, "room", Severity.WARN), () -> ps.toString());
        assertFalse(ClashDetector.refuses(ps), "two classes may genuinely share a hall");
    }

    @Test
    @DisplayName("a gradeId that disagrees with the subject's grade is REFUSED — D2's guard")
    void grade_must_match_the_subject() {
        // This is the check that keeps the denormalised gradeId a cache rather than a second truth.
        List<Problem> ps = ClashDetector.check(
                entry(null, GRADE_5A, MATHS_5A, MRS_KHAN, "12"), List.of(), ctxFor(GRADE_6B));
        assertTrue(has(ps, "gradeId", Severity.REFUSE), () -> ps.toString());
    }

    @Test
    @DisplayName("editing an entry does not clash with itself")
    void edit_ignores_itself() {
        TimetableEntry saved = entry(7L, GRADE_5A, MATHS_5A, MRS_KHAN, "12");
        TimetableEntry edited = entry(7L, GRADE_5A, MATHS_5A, MRS_KHAN, "14");   // just moved room
        assertTrue(ClashDetector.check(edited, List.of(saved), ctxFor(GRADE_5A)).isEmpty());
    }

    @Test
    @DisplayName("a different day or period is not a clash")
    void different_slot_is_fine() {
        TimetableEntry other = TimetableEntry.builder()
                .id(1L).termId(1L).dayOfWeek(DayOfWeek.TUESDAY).periodId(PERIOD_3)
                .gradeId(GRADE_5A).subjectId(ENGLISH_5A).staffId(MRS_KHAN).room("12")
                .userId(1L).organizationId(1L).build();
        assertTrue(ClashDetector.check(
                entry(null, GRADE_5A, MATHS_5A, MRS_KHAN, "12"), List.of(other), ctxFor(GRADE_5A)).isEmpty());
    }

    @Test
    @DisplayName("entries in DIFFERENT terms never clash")
    void different_term_is_not_a_clash() {
        TimetableEntry otherTerm = TimetableEntry.builder()
                .id(1L).termId(2L).dayOfWeek(DayOfWeek.MONDAY).periodId(PERIOD_3)
                .gradeId(GRADE_5A).subjectId(ENGLISH_5A).staffId(MRS_KHAN).room("12")
                .userId(1L).organizationId(1L).build();
        assertTrue(ClashDetector.check(
                entry(null, GRADE_5A, MATHS_5A, MRS_KHAN, "12"), List.of(otherTerm), ctxFor(GRADE_5A)).isEmpty());
    }

    @Test
    @DisplayName("two term-less entries in the same slot DO clash — the UNIQUE key cannot see this")
    void null_term_still_clashes() {
        // MySQL does not treat NULLs as equal, so uk_tt_grade_slot never fires for a term-less tenant.
        // For those schools this validator is the ONLY defence, which is exactly why it is tested.
        TimetableEntry existing = TimetableEntry.builder()
                .id(1L).termId(null).dayOfWeek(DayOfWeek.MONDAY).periodId(PERIOD_3)
                .gradeId(GRADE_5A).subjectId(ENGLISH_5A).staffId(MR_ALI).room("12")
                .userId(1L).organizationId(1L).build();
        TimetableEntry candidate = TimetableEntry.builder()
                .termId(null).dayOfWeek(DayOfWeek.MONDAY).periodId(PERIOD_3)
                .gradeId(GRADE_5A).subjectId(MATHS_5A).staffId(MRS_KHAN).room("13")
                .userId(1L).organizationId(1L).build();
        assertTrue(ClashDetector.refuses(ClashDetector.check(candidate, List.of(existing), ctxFor(GRADE_5A))));
    }

    @Test
    @DisplayName("an unassigned teacher never triggers a teacher clash")
    void null_staff_is_not_a_clash() {
        // Two slots may both be awaiting a teacher; that is not two people in one room.
        TimetableEntry existing = entry(1L, GRADE_6B, 601L, null, "20");
        List<Problem> ps = ClashDetector.check(
                entry(null, GRADE_5A, MATHS_5A, null, "12"), List.of(existing), ctxFor(GRADE_5A));
        assertFalse(has(ps, "staffId", Severity.REFUSE), () -> ps.toString());
    }

    @Test
    @DisplayName("a period outside the class's or teacher's window WARNS")
    void outside_window_warns() {
        Context ctx = new Context(GRADE_5A,
                LocalTime.of(8, 0), LocalTime.of(13, 0),      // class day
                LocalTime.of(8, 0), LocalTime.of(12, 0),      // teacher hours
                LocalTime.of(12, 30), LocalTime.of(13, 0),    // the period being placed
                null, null, null);
        List<Problem> ps = ClashDetector.check(
                entry(null, GRADE_5A, MATHS_5A, MRS_KHAN, "12"), List.of(), ctx);
        assertTrue(has(ps, "staffId", Severity.WARN), () -> "teacher window: " + ps);
        assertFalse(ClashDetector.refuses(ps), "loosely-maintained time fields must not block a save");
    }

    @Test
    @DisplayName("an unrecorded time window is not a warning")
    void missing_window_is_silent() {
        // Most of these fields are blank in practice; warning on every blank would bury the real ones.
        Context ctx = new Context(GRADE_5A, null, null, null, null,
                LocalTime.of(12, 30), LocalTime.of(13, 0), null, null, null);
        assertTrue(ClashDetector.check(
                entry(null, GRADE_5A, MATHS_5A, MRS_KHAN, "12"), List.of(), ctx).isEmpty());
    }

    @Test
    @DisplayName("every problem is reported at once, not one save at a time")
    void reports_all_problems_together() {
        TimetableEntry existing = entry(1L, GRADE_5A, ENGLISH_5A, MRS_KHAN, "Hall");
        List<Problem> ps = ClashDetector.check(
                entry(null, GRADE_5A, MATHS_5A, MRS_KHAN, "Hall"), List.of(existing), ctxFor(GRADE_5A));
        assertTrue(has(ps, "staffId", Severity.REFUSE));
        assertTrue(has(ps, "periodId", Severity.REFUSE));
        assertTrue(has(ps, "room", Severity.WARN));
        assertFalse(ClashDetector.refusalMessage(ps).isBlank());
        assertFalse(ClashDetector.warningMessage(ps).isBlank(), "warnings survive alongside refusals");
    }
}
