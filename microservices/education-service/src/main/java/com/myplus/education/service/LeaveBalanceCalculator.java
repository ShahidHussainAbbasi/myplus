package com.myplus.education.service;

import com.myplus.education.entity.LeaveRequest;
import com.myplus.education.entity.LeaveRequestStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Slice 2.3 — how many leave days are left, and how many a request will consume.
 *
 * <p>Pure: every input is an argument, so the arithmetic a teacher will argue about is testable with no
 * Spring, no database and no Docker. Same shape as {@code ClashDetector} (2.1), {@code FreeTeacherFinder}
 * (2.2) and {@code PromotionPolicy} (1.6).
 *
 * <h3>D1 — the balance is DERIVED, never stored</h3>
 *
 * {@code balance = quota − approved days taken this year}. A stored balance is a cache of a sum, and the
 * moment a request is cancelled, back-dated or corrected it is wrong with nothing saying so. Deriving it
 * costs a grouped query and cannot drift — 1.4 D4's reasoning (grading is derived) applied to the number
 * people count.
 *
 * <h3>D4 — which days actually count</h3>
 *
 * A range is expanded to days, and days <b>outside every term</b> are skipped: the school is not in session,
 * so it is not leave. <b>Weekends are deliberately NOT skipped</b> — "which days are the weekend" is
 * Friday–Saturday across much of the region this platform serves, and guessing wrong silently deducts the
 * wrong number of days from someone's entitlement. Until a holiday calendar exists (slice §6), a leave day
 * landing on a non-working day stays <b>visible and correctable</b> rather than silently swallowed, which is
 * the safer failure for a number that is counted.
 */
public final class LeaveBalanceCalculator {

    private LeaveBalanceCalculator() { }

    /** A term's span, as the calculator needs it — no entity dependency, so this stays pure. */
    public record TermRange(LocalDate start, LocalDate end) {
        boolean contains(LocalDate d) {
            return start != null && end != null && !d.isBefore(start) && !d.isAfter(end);
        }
    }

    /**
     * One line of the balance screen.
     *
     * @param quota    null when the type is uncapped (unpaid leave usually is)
     * @param taken    approved days consumed this year
     * @param remaining null when uncapped — NOT zero, which would read as "none left"
     */
    public record Balance(Long leaveTypeId, String leaveTypeName, Integer quota, int taken, Integer remaining) { }

    /**
     * The days a range consumes: every date from/to inclusive that falls inside some term.
     *
     * <p>With no terms defined the whole range counts — 1.1's rule that a school without terms keeps
     * working. Refusing to count, or counting zero, would both be worse than counting the calendar days the
     * teacher actually asked for.
     */
    public static int workingDaysIn(LocalDate from, LocalDate to, Collection<TermRange> terms) {
        if (from == null || to == null || to.isBefore(from)) return 0;
        boolean noTerms = terms == null || terms.isEmpty();
        int days = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            // `d` is the loop variable, so it is not effectively final and cannot be captured directly.
            final LocalDate day = d;
            if (noTerms || terms.stream().anyMatch(t -> t.contains(day))) days++;
        }
        return days;
    }

    /** The dates a range covers that are in session — what approval expands into daily absences (D3/D4). */
    public static List<LocalDate> sessionDaysIn(LocalDate from, LocalDate to, Collection<TermRange> terms) {
        List<LocalDate> out = new ArrayList<>();
        if (from == null || to == null || to.isBefore(from)) return out;
        boolean noTerms = terms == null || terms.isEmpty();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            final LocalDate day = d;
            if (noTerms || terms.stream().anyMatch(t -> t.contains(day))) out.add(day);
        }
        return out;
    }

    /**
     * Approved days taken of one leave type in one calendar year.
     *
     * <p>Counts <b>APPROVED only</b>: a PENDING request has not been granted, and a REJECTED or CANCELLED
     * one never was. Counting pending requests would show a balance the teacher has not actually spent, and
     * counting rejected ones would penalise them for asking.
     *
     * <p>Uses {@code daysCounted} when the decision recorded it, falling back to the raw span. The stored
     * figure is what was actually granted; re-deriving it later would give a different answer once the term
     * calendar changes, which is why the decision records it.
     */
    public static int daysTaken(Collection<LeaveRequest> requests, Long leaveTypeId, int year) {
        int total = 0;
        for (LeaveRequest r : requests == null ? List.<LeaveRequest>of() : requests) {
            if (r == null || r.getStatus() != LeaveRequestStatus.APPROVED) continue;
            if (!Objects.equals(r.getLeaveTypeId(), leaveTypeId)) continue;
            if (r.getFromDate() == null || r.getFromDate().getYear() != year) continue;
            total += r.getDaysCounted() != null ? r.getDaysCounted() : rawSpan(r);
        }
        return total;
    }

    /** quota − taken, or null remaining when the type is uncapped. */
    public static Balance balanceFor(Long leaveTypeId, String leaveTypeName, Integer quota,
                                     Collection<LeaveRequest> requests, int year) {
        int taken = daysTaken(requests, leaveTypeId, year);
        Integer remaining = quota == null ? null : quota - taken;
        return new Balance(leaveTypeId, leaveTypeName, quota, taken, remaining);
    }

    /**
     * How far a request exceeds its balance, or 0 when it fits.
     *
     * <p>D5 — this WARNS, it does not block. A teacher with two days left asking for five is a conversation,
     * not an error; the system's job is to make the overage impossible to miss at the point of approval.
     * An uncapped type can never be over.
     */
    public static int overageFor(Integer quota, int alreadyTaken, int requestedDays) {
        if (quota == null) return 0;
        int over = (alreadyTaken + requestedDays) - quota;
        return Math.max(over, 0);
    }

    private static int rawSpan(LeaveRequest r) {
        if (r.getFromDate() == null || r.getToDate() == null) return 0;
        if (r.getToDate().isBefore(r.getFromDate())) return 0;
        return (int) (r.getToDate().toEpochDay() - r.getFromDate().toEpochDay()) + 1;
    }
}
