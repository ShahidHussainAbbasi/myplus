package com.myplus.education.service;

import com.myplus.education.entity.Substitution;
import com.myplus.education.entity.SubstitutionStatus;
import com.myplus.education.entity.TimetableEntry;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Slice 2.2 — who could cover this lesson?
 *
 * <p>Pure: every input is an argument, so the rule that decides who gets pulled into a classroom is
 * testable with no Spring, no database and no Docker. Same shape as {@code ClashDetector} (2.1),
 * {@code PromotionPolicy} (1.6) and {@code TermAggregator} (1.5).
 *
 * <h3>D3 — free is COMPUTED, never stored</h3>
 *
 * <pre>
 *   free = all staff
 *          − teaching in this slot        (the timetable)
 *          − absent today                 (staff_absence)
 *          − already covering in this slot (substitution)   ← the one a naive query misses
 * </pre>
 *
 * The third exclusion is the interesting one: a teacher assigned to cover period 3 is no longer free in
 * period 3, but nothing in the <i>timetable</i> says so — the cover exists only in the substitution table.
 * Omitting it double-books the very person the screen just freed up.
 *
 * <p>Caching any of this would be wrong: it changes every time an absence is marked or a cover assigned,
 * which is continuously, on the one morning it matters.
 *
 * <h3>D3 — suggest, never auto-assign</h3>
 *
 * This returns candidates, ranked by what the database actually knows. It does not choose. A head knows
 * that Mr Ali already has three covers this week, that Mrs Iqbal teaches this subject, and that someone
 * has a hospital appointment at 11 — and only the middle fact is in here.
 */
public final class FreeTeacherFinder {

    private FreeTeacherFinder() { }

    /**
     * One suggestion, with the facts behind it so the UI can explain the order rather than assert it.
     *
     * @param teachesThisSubject the candidate already teaches this subject to some class — the strongest
     *                           signal available, and the reason ranking is worth doing at all
     * @param coversToday        how many covers they have already been given today; lower is fairer
     */
    public record Candidate(Long staffId, String staffName, boolean teachesThisSubject, int coversToday) { }

    /**
     * The staff who could take {@code slot}, best first.
     *
     * @param allStaff        every staff member in scope, as {@code [id, name]} pairs
     * @param termTimetable   every timetable entry for the term — read ONCE by the caller, not per candidate
     * @param day             the weekday of the date being covered
     * @param periodId        the period being covered
     * @param subjectId       the subject being covered, for the ranking hint; may be null
     * @param absentStaffIds  everyone marked absent on that date
     * @param subsOnDate      substitutions already recorded for that date
     * @param entryById       lookup for resolving a substitution back to its lesson's slot
     */
    public static List<Candidate> freeIn(List<Object[]> allStaff,
                                         List<TimetableEntry> termTimetable,
                                         DayOfWeek day,
                                         Long periodId,
                                         Long subjectId,
                                         Collection<Long> absentStaffIds,
                                         Collection<Substitution> subsOnDate,
                                         java.util.Map<Long, TimetableEntry> entryById) {

        Set<Long> busy = new HashSet<>();

        // 1. teaching in this slot, per the timetable
        for (TimetableEntry e : nullSafe(termTimetable)) {
            if (e.getStaffId() == null) continue;
            if (e.getDayOfWeek() == day && Objects.equals(e.getPeriodId(), periodId)) {
                busy.add(e.getStaffId());
            }
        }

        // 2. absent that day
        for (Long id : nullSafe(absentStaffIds)) if (id != null) busy.add(id);

        // 3. already covering something in this slot — invisible to the timetable (see the class javadoc)
        java.util.Map<Long, Integer> coverCount = new java.util.HashMap<>();
        for (Substitution s : nullSafe(subsOnDate)) {
            if (s.getCoverStaffId() == null || s.getStatus() != SubstitutionStatus.ASSIGNED) continue;
            coverCount.merge(s.getCoverStaffId(), 1, Integer::sum);
            TimetableEntry covered = entryById == null ? null : entryById.get(s.getTimetableEntryId());
            if (covered != null && covered.getDayOfWeek() == day
                    && Objects.equals(covered.getPeriodId(), periodId)) {
                busy.add(s.getCoverStaffId());
            }
        }

        // Which staff already teach the subject anywhere — the ranking hint.
        Set<Long> teachesSubject = new LinkedHashSet<>();
        if (subjectId != null) {
            for (TimetableEntry e : nullSafe(termTimetable)) {
                if (e.getStaffId() != null && Objects.equals(e.getSubjectId(), subjectId)) {
                    teachesSubject.add(e.getStaffId());
                }
            }
        }

        List<Candidate> out = new ArrayList<>();
        for (Object[] row : nullSafe(allStaff)) {
            if (row == null || row.length < 2 || row[0] == null) continue;
            Long id = ((Number) row[0]).longValue();
            if (busy.contains(id)) continue;
            out.add(new Candidate(id, row[1] == null ? null : row[1].toString(),
                    teachesSubject.contains(id), coverCount.getOrDefault(id, 0)));
        }

        // Subject match first, then whoever has covered least today — a fairness hint, not a rule.
        out.sort((a, b) -> {
            if (a.teachesThisSubject() != b.teachesThisSubject()) return a.teachesThisSubject() ? -1 : 1;
            if (a.coversToday() != b.coversToday()) return Integer.compare(a.coversToday(), b.coversToday());
            return String.valueOf(a.staffName()).compareToIgnoreCase(String.valueOf(b.staffName()));
        });
        return out;
    }

    private static <T> Collection<T> nullSafe(Collection<T> c) { return c == null ? List.of() : c; }
    private static <T> List<T> nullSafe(List<T> c) { return c == null ? List.of() : c; }
}
