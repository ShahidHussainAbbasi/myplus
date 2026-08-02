package com.myplus.education.service;

import com.myplus.education.entity.Substitution;
import com.myplus.education.entity.SubstitutionStatus;
import com.myplus.education.entity.TimetableEntry;
import com.myplus.education.service.FreeTeacherFinder.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 2.2 — who can cover.
 *
 * Pure: no Spring, no database, no Docker, so it runs on every {@code mvn test}. This decides who gets
 * pulled into a classroom at 07:50, so the exclusions are tested individually rather than in aggregate.
 */
class FreeTeacherFinderTest {

    private static final long KHAN = 1L, ALI = 2L, IQBAL = 3L, ZARA = 4L;
    private static final long PERIOD_3 = 30L, PERIOD_4 = 40L;
    private static final long MATHS = 100L, ENGLISH = 200L;
    private static final DayOfWeek TUE = DayOfWeek.TUESDAY;

    private static List<Object[]> staff() {
        return List.of(
                new Object[] { KHAN, "Khan" },
                new Object[] { ALI, "Ali" },
                new Object[] { IQBAL, "Iqbal" },
                new Object[] { ZARA, "Zara" });
    }

    private static TimetableEntry lesson(long id, DayOfWeek day, long periodId, Long staffId, long subjectId) {
        return TimetableEntry.builder()
                .id(id).termId(1L).dayOfWeek(day).periodId(periodId)
                .subjectId(subjectId).gradeId(9L).staffId(staffId)
                .userId(1L).organizationId(1L).build();
    }

    private static Substitution cover(long entryId, Long coverStaffId, SubstitutionStatus status) {
        return Substitution.builder()
                .timetableEntryId(entryId).subDate(LocalDate.of(2026, 8, 4))
                .coverStaffId(coverStaffId).status(status)
                .userId(1L).organizationId(1L).build();
    }

    private static Map<Long, TimetableEntry> index(List<TimetableEntry> entries) {
        Map<Long, TimetableEntry> m = new HashMap<>();
        for (TimetableEntry e : entries) m.put(e.getId(), e);
        return m;
    }

    private static List<Long> ids(List<Candidate> cs) {
        return cs.stream().map(Candidate::staffId).toList();
    }

    @Test
    @DisplayName("with nothing scheduled, everyone is free")
    void everyone_free() {
        List<Candidate> free = FreeTeacherFinder.freeIn(
                staff(), List.of(), TUE, PERIOD_3, MATHS, List.of(), List.of(), Map.of());
        assertEquals(4, free.size());
    }

    @Test
    @DisplayName("a teacher already teaching in that slot is excluded")
    void teaching_in_slot_excluded() {
        List<TimetableEntry> tt = List.of(lesson(1, TUE, PERIOD_3, ALI, ENGLISH));
        List<Candidate> free = FreeTeacherFinder.freeIn(
                staff(), tt, TUE, PERIOD_3, MATHS, List.of(), List.of(), index(tt));
        assertFalse(ids(free).contains(ALI));
    }

    @Test
    @DisplayName("teaching in a DIFFERENT period or day does not exclude")
    void teaching_elsewhere_is_fine() {
        List<TimetableEntry> tt = List.of(
                lesson(1, TUE, PERIOD_4, ALI, ENGLISH),
                lesson(2, DayOfWeek.WEDNESDAY, PERIOD_3, IQBAL, ENGLISH));
        List<Candidate> free = FreeTeacherFinder.freeIn(
                staff(), tt, TUE, PERIOD_3, MATHS, List.of(), List.of(), index(tt));
        assertTrue(ids(free).containsAll(List.of(ALI, IQBAL)));
    }

    @Test
    @DisplayName("an absent teacher is excluded even though the timetable says they are free")
    void absent_excluded() {
        List<Candidate> free = FreeTeacherFinder.freeIn(
                staff(), List.of(), TUE, PERIOD_3, MATHS, List.of(IQBAL), List.of(), Map.of());
        assertFalse(ids(free).contains(IQBAL), "someone off sick cannot cover");
    }

    @Test
    @DisplayName("a teacher ALREADY COVERING in that slot is excluded — the case a naive query misses")
    void already_covering_in_slot_excluded() {
        // Nothing in the TIMETABLE says Zara is busy: the cover exists only in the substitution table.
        // Miss this and the screen double-books the very person it just assigned.
        List<TimetableEntry> tt = List.of(lesson(7, TUE, PERIOD_3, KHAN, ENGLISH));
        List<Substitution> subs = List.of(cover(7, ZARA, SubstitutionStatus.ASSIGNED));
        List<Candidate> free = FreeTeacherFinder.freeIn(
                staff(), tt, TUE, PERIOD_3, MATHS, List.of(), subs, index(tt));
        assertFalse(ids(free).contains(ZARA));
    }

    @Test
    @DisplayName("covering a DIFFERENT period does not exclude, but does count towards their load")
    void covering_elsewhere_counts_but_does_not_exclude() {
        List<TimetableEntry> tt = List.of(lesson(8, TUE, PERIOD_4, KHAN, ENGLISH));
        List<Substitution> subs = List.of(cover(8, ZARA, SubstitutionStatus.ASSIGNED));
        List<Candidate> free = FreeTeacherFinder.freeIn(
                staff(), tt, TUE, PERIOD_3, MATHS, List.of(), subs, index(tt));
        Candidate zara = free.stream().filter(c -> c.staffId() == ZARA).findFirst().orElseThrow();
        assertEquals(1, zara.coversToday(), "their load is known even when they stay eligible");
    }

    @Test
    @DisplayName("a CANCELLED cover frees the teacher again")
    void cancelled_cover_does_not_exclude() {
        List<TimetableEntry> tt = List.of(lesson(7, TUE, PERIOD_3, KHAN, ENGLISH));
        List<Substitution> subs = List.of(cover(7, ZARA, SubstitutionStatus.CANCELLED));
        List<Candidate> free = FreeTeacherFinder.freeIn(
                staff(), tt, TUE, PERIOD_3, MATHS, List.of(), subs, index(tt));
        assertTrue(ids(free).contains(ZARA));
    }

    @Test
    @DisplayName("an UNCOVERED row excludes nobody — it has no cover teacher to be busy")
    void uncovered_row_excludes_nobody() {
        List<TimetableEntry> tt = List.of(lesson(7, TUE, PERIOD_3, KHAN, ENGLISH));
        List<Substitution> subs = List.of(cover(7, null, SubstitutionStatus.UNCOVERED));
        List<Candidate> free = FreeTeacherFinder.freeIn(
                staff(), tt, TUE, PERIOD_3, MATHS, List.of(), subs, index(tt));
        assertEquals(4, free.size());
    }

    @Test
    @DisplayName("someone who teaches the subject is ranked first")
    void subject_match_ranks_first() {
        // Iqbal teaches Maths on another day; the slot itself is free for everyone.
        List<TimetableEntry> tt = List.of(lesson(1, DayOfWeek.MONDAY, PERIOD_4, IQBAL, MATHS));
        List<Candidate> free = FreeTeacherFinder.freeIn(
                staff(), tt, TUE, PERIOD_3, MATHS, List.of(), List.of(), index(tt));
        assertEquals(IQBAL, free.get(0).staffId());
        assertTrue(free.get(0).teachesThisSubject());
    }

    @Test
    @DisplayName("among equals, whoever has covered least today comes first")
    void fewer_covers_ranks_first() {
        List<TimetableEntry> tt = List.of(lesson(8, TUE, PERIOD_4, KHAN, ENGLISH));
        List<Substitution> subs = List.of(cover(8, ALI, SubstitutionStatus.ASSIGNED));
        List<Candidate> free = FreeTeacherFinder.freeIn(
                staff(), tt, TUE, PERIOD_3, MATHS, List.of(), subs, index(tt));
        // Ali already covers one today, so he must not be the first suggestion.
        assertNotEquals(ALI, free.get(0).staffId());
    }

    @Test
    @DisplayName("an unassigned lesson (no teacher) never makes anyone busy")
    void null_staff_on_a_lesson_is_ignored() {
        List<TimetableEntry> tt = List.of(lesson(1, TUE, PERIOD_3, null, ENGLISH));
        List<Candidate> free = FreeTeacherFinder.freeIn(
                staff(), tt, TUE, PERIOD_3, MATHS, List.of(), List.of(), index(tt));
        assertEquals(4, free.size());
    }

    @Test
    @DisplayName("null inputs are tolerated — the screen must render on an empty school")
    void null_inputs_are_safe() {
        assertTrue(FreeTeacherFinder.freeIn(null, null, TUE, PERIOD_3, null, null, null, null).isEmpty());
        assertEquals(4, FreeTeacherFinder.freeIn(
                staff(), null, TUE, PERIOD_3, null, null, null, null).size());
    }
}
