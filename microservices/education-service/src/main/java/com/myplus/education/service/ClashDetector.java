package com.myplus.education.service;

import com.myplus.education.entity.TimetableEntry;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Slice 2.1 — can this lesson go in this slot?
 *
 * <p>Pure: every input is an argument, so the rules that decide where a teacher stands are testable with
 * no Spring, no database and no Docker. Same shape as {@code BandValidator} (1.4), {@code MarksValidator}
 * (1.3) and {@code PromotionPolicy} (1.6).
 *
 * <h3>D3 — three clashes, and they are NOT equally serious</h3>
 *
 * <ul>
 *   <li><b>Teacher</b> in two places at once — {@code REFUSE}. Physically impossible.</li>
 *   <li><b>Class</b> in two places at once — {@code REFUSE}. Physically impossible.</li>
 *   <li><b>Room</b> double-booked — {@code WARN}. {@code Grade.room} is a bare number with no room master
 *       (D6), so the data is too weak to refuse on: two classes may genuinely share a hall, and blocking
 *       that would teach people to work around the timetable.</li>
 * </ul>
 *
 * The same reasoning governs the two time-window checks: {@code Grade.timeFrom/timeTo} and
 * {@code Staff.timeIn/timeOut} exist but are loosely maintained, so being outside them warns. A hard
 * refusal on data nobody curates is a refusal people learn to route around.
 */
public final class ClashDetector {

    private ClashDetector() { }

    public enum Severity { REFUSE, WARN }

    /** One problem with a candidate slot. {@code field} lets the UI mark the cell that caused it. */
    public record Problem(String field, String message, Severity severity) {
        public boolean refuses() { return severity == Severity.REFUSE; }
    }

    /**
     * Everything the check needs that does not live on the entry itself. Passed in rather than looked up,
     * so this class stays pure and the caller reads each table exactly once per save.
     *
     * @param subjectGradeId the grade of the candidate's subject — what {@code entry.gradeId} must equal
     * @param gradeFrom      the class's daily window start, or null if not recorded
     * @param staffFrom      the teacher's working-hours start, or null if not recorded
     */
    public record Context(Long subjectGradeId, LocalTime gradeFrom, LocalTime gradeTo,
                          LocalTime staffFrom, LocalTime staffTo,
                          LocalTime periodStart, LocalTime periodEnd,
                          String otherClassInRoom, String otherClassForStaff, String otherSubjectForClass) { }

    /**
     * Validate a candidate against the slots already scheduled.
     *
     * @param candidate the entry being saved; its id is null on create and set on edit
     * @param existing  every entry already in the same term — the caller loads this ONCE, not per check
     * @param ctx       the resolved names and windows (see {@link Context})
     * @return every problem, refusals and warnings together, so the UI can show them all at once rather
     *         than making the user fix them one save at a time
     */
    public static List<Problem> check(TimetableEntry candidate, List<TimetableEntry> existing, Context ctx) {
        List<Problem> problems = new ArrayList<>();
        if (candidate == null) return problems;

        // ── D2's guard: the stored class must agree with the subject it came from ───────────────────
        // This is what keeps the denormalised gradeId a cache rather than a second truth. Removing it
        // re-opens exactly the drift 1.2 D2 warned about.
        if (ctx != null && ctx.subjectGradeId() != null
                && !Objects.equals(candidate.getGradeId(), ctx.subjectGradeId())) {
            problems.add(new Problem("gradeId",
                    "This subject belongs to a different class. Pick a subject that belongs to the class "
                            + "being timetabled.", Severity.REFUSE));
        }

        for (TimetableEntry other : existing == null ? List.<TimetableEntry>of() : existing) {
            if (other == null) continue;
            // An edit must not clash with itself.
            if (candidate.getId() != null && candidate.getId().equals(other.getId())) continue;
            if (!sameSlot(candidate, other)) continue;

            if (candidate.getStaffId() != null && Objects.equals(candidate.getStaffId(), other.getStaffId())) {
                problems.add(new Problem("staffId",
                        "That teacher is already teaching "
                                + describe(ctx == null ? null : ctx.otherClassForStaff(), "another class")
                                + " in this period.", Severity.REFUSE));
            }
            if (Objects.equals(candidate.getGradeId(), other.getGradeId())) {
                problems.add(new Problem("periodId",
                        "This class already has "
                                + describe(ctx == null ? null : ctx.otherSubjectForClass(), "another subject")
                                + " in this period.", Severity.REFUSE));
            }
            if (hasText(candidate.getRoom()) && candidate.getRoom().equalsIgnoreCase(other.getRoom())) {
                problems.add(new Problem("room",
                        "Room " + candidate.getRoom() + " is also used by "
                                + describe(ctx == null ? null : ctx.otherClassInRoom(), "another class")
                                + " in this period.", Severity.WARN));
            }
        }

        if (ctx != null) {
            outsideWindow(ctx.periodStart(), ctx.periodEnd(), ctx.gradeFrom(), ctx.gradeTo())
                    .ifPresent(msg -> problems.add(new Problem("periodId",
                            "This period falls outside the class's timings (" + msg + ").", Severity.WARN)));
            outsideWindow(ctx.periodStart(), ctx.periodEnd(), ctx.staffFrom(), ctx.staffTo())
                    .ifPresent(msg -> problems.add(new Problem("staffId",
                            "This period falls outside the teacher's hours (" + msg + ").", Severity.WARN)));
        }
        return problems;
    }

    /** True when two entries occupy the same cell of the grid — the equality D1 exists to make possible. */
    private static boolean sameSlot(TimetableEntry a, TimetableEntry b) {
        return Objects.equals(a.getTermId(), b.getTermId())
                && a.getDayOfWeek() == b.getDayOfWeek()
                && Objects.equals(a.getPeriodId(), b.getPeriodId());
    }

    /**
     * A period sitting outside an availability window, or empty when it fits or the window is unrecorded.
     * Unrecorded is NOT a problem: most of these fields are blank in practice, and warning about every
     * blank one would bury the warnings that matter.
     */
    private static java.util.Optional<String> outsideWindow(LocalTime periodStart, LocalTime periodEnd,
                                                            LocalTime from, LocalTime to) {
        if (periodStart == null || periodEnd == null || from == null || to == null) {
            return java.util.Optional.empty();
        }
        if (periodStart.isBefore(from) || periodEnd.isAfter(to)) {
            return java.util.Optional.of(from + "–" + to);
        }
        return java.util.Optional.empty();
    }

    private static String describe(String name, String fallback) {
        return hasText(name) ? name : fallback;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** Convenience for callers: does this list block the save? */
    public static boolean refuses(List<Problem> problems) {
        return problems != null && problems.stream().anyMatch(Problem::refuses);
    }

    /** The refusal text, joined — callers answer with a single GenericResponse message. */
    public static String refusalMessage(List<Problem> problems) {
        List<String> msgs = new ArrayList<>();
        for (Problem p : problems) if (p.refuses()) msgs.add(p.message());
        return String.join(" ", msgs);
    }

    /** The warning text, joined — shown alongside a SUCCESS, never instead of it. */
    public static String warningMessage(List<Problem> problems) {
        List<String> msgs = new ArrayList<>();
        for (Problem p : problems) if (!p.refuses()) msgs.add(p.message());
        return String.join(" ", msgs);
    }
}
