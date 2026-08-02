package com.myplus.education.service;

import com.myplus.education.entity.LeaveRequest;
import com.myplus.education.entity.LeaveRequestStatus;
import com.myplus.education.service.LeaveBalanceCalculator.Balance;
import com.myplus.education.service.LeaveBalanceCalculator.TermRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 2.3 — leave arithmetic.
 *
 * Pure: no Spring, no database, no Docker, so it runs on every {@code mvn test}. This is the number a
 * teacher will argue about, which is the strongest possible reason for it to be testable in isolation.
 */
class LeaveBalanceCalculatorTest {

    private static final long CASUAL = 1L, UNPAID = 2L;
    private static final int YEAR = 2026;

    private static LeaveRequest req(long typeId, String from, String to, LeaveRequestStatus status,
                                    Integer daysCounted) {
        return LeaveRequest.builder()
                .staffId(1L).leaveTypeId(typeId)
                .fromDate(LocalDate.parse(from)).toDate(LocalDate.parse(to))
                .daysCounted(daysCounted).status(status)
                .userId(1L).organizationId(1L).build();
    }

    private static TermRange term(String start, String end) {
        return new TermRange(LocalDate.parse(start), LocalDate.parse(end));
    }

    // ── day counting (D4) ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a range inside a term counts every calendar day, weekends included")
    void counts_calendar_days_in_term() {
        // Deliberate: "which days are the weekend" is Friday–Saturday in much of the region this ships to,
        // so skipping Sat/Sun would silently deduct the wrong number of days from someone's entitlement.
        List<TermRange> terms = List.of(term("2026-08-01", "2026-12-20"));
        assertEquals(3, LeaveBalanceCalculator.workingDaysIn(
                LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-09"), terms));
        assertEquals(7, LeaveBalanceCalculator.workingDaysIn(
                LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-13"), terms),
                "a full week is 7 days, not 5 — no weekend assumption");
    }

    @Test
    @DisplayName("days outside every term are skipped — the school is not in session")
    void skips_days_outside_every_term() {
        List<TermRange> terms = List.of(term("2026-08-01", "2026-08-31"));
        // Aug 30–Sep 2: only the 30th and 31st are in session.
        assertEquals(2, LeaveBalanceCalculator.workingDaysIn(
                LocalDate.parse("2026-08-30"), LocalDate.parse("2026-09-02"), terms));
    }

    @Test
    @DisplayName("with NO terms defined the whole range counts — a school without terms keeps working")
    void no_terms_counts_everything() {
        // 1.1's rule. Counting zero would be worse than counting what the teacher actually asked for.
        assertEquals(3, LeaveBalanceCalculator.workingDaysIn(
                LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-09"), List.of()));
        assertEquals(3, LeaveBalanceCalculator.workingDaysIn(
                LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-09"), null));
    }

    @Test
    @DisplayName("a single-day request counts as one, and a reversed range counts as none")
    void edges_of_a_range() {
        assertEquals(1, LeaveBalanceCalculator.workingDaysIn(
                LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-07"), null));
        assertEquals(0, LeaveBalanceCalculator.workingDaysIn(
                LocalDate.parse("2026-09-09"), LocalDate.parse("2026-09-07"), null));
        assertEquals(0, LeaveBalanceCalculator.workingDaysIn(null, null, null));
    }

    @Test
    @DisplayName("sessionDaysIn lists exactly the dates approval will expand into absences")
    void session_days_are_the_expansion() {
        List<TermRange> terms = List.of(term("2026-08-01", "2026-08-31"));
        List<LocalDate> days = LeaveBalanceCalculator.sessionDaysIn(
                LocalDate.parse("2026-08-30"), LocalDate.parse("2026-09-02"), terms);
        assertEquals(List.of(LocalDate.parse("2026-08-30"), LocalDate.parse("2026-08-31")), days);
    }

    // ── days taken ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("only APPROVED requests consume the balance")
    void only_approved_counts() {
        List<LeaveRequest> rs = List.of(
                req(CASUAL, "2026-03-02", "2026-03-04", LeaveRequestStatus.APPROVED, 3),
                req(CASUAL, "2026-04-01", "2026-04-05", LeaveRequestStatus.PENDING, 5),
                req(CASUAL, "2026-05-01", "2026-05-02", LeaveRequestStatus.REJECTED, 2),
                req(CASUAL, "2026-06-01", "2026-06-02", LeaveRequestStatus.CANCELLED, 2));
        // Pending is not yet spent; rejected and cancelled never were — counting either would be wrong,
        // and counting rejected would penalise someone for asking.
        assertEquals(3, LeaveBalanceCalculator.daysTaken(rs, CASUAL, YEAR));
    }

    @Test
    @DisplayName("another leave type's days do not count against this one")
    void types_are_separate() {
        List<LeaveRequest> rs = List.of(
                req(CASUAL, "2026-03-02", "2026-03-04", LeaveRequestStatus.APPROVED, 3),
                req(UNPAID, "2026-03-10", "2026-03-20", LeaveRequestStatus.APPROVED, 11));
        assertEquals(3, LeaveBalanceCalculator.daysTaken(rs, CASUAL, YEAR));
        assertEquals(11, LeaveBalanceCalculator.daysTaken(rs, UNPAID, YEAR));
    }

    @Test
    @DisplayName("another year's leave does not count against this year")
    void years_are_separate() {
        List<LeaveRequest> rs = List.of(
                req(CASUAL, "2025-03-02", "2025-03-04", LeaveRequestStatus.APPROVED, 3),
                req(CASUAL, "2026-03-02", "2026-03-03", LeaveRequestStatus.APPROVED, 2));
        assertEquals(2, LeaveBalanceCalculator.daysTaken(rs, CASUAL, YEAR));
    }

    @Test
    @DisplayName("daysCounted wins over the raw span — it is what was actually granted")
    void stored_count_wins() {
        // The range spans 4 days but only 2 were in session when it was approved. Re-deriving it now would
        // give a different answer if the term calendar has since changed, which is why it was recorded.
        List<LeaveRequest> rs = List.of(
                req(CASUAL, "2026-08-30", "2026-09-02", LeaveRequestStatus.APPROVED, 2));
        assertEquals(2, LeaveBalanceCalculator.daysTaken(rs, CASUAL, YEAR));
    }

    @Test
    @DisplayName("with no stored count, the raw span is the fallback")
    void falls_back_to_the_span() {
        List<LeaveRequest> rs = List.of(
                req(CASUAL, "2026-03-02", "2026-03-04", LeaveRequestStatus.APPROVED, null));
        assertEquals(3, LeaveBalanceCalculator.daysTaken(rs, CASUAL, YEAR));
    }

    // ── balance and overage ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("balance is quota minus taken")
    void balance_is_derived() {
        List<LeaveRequest> rs = List.of(
                req(CASUAL, "2026-03-02", "2026-03-04", LeaveRequestStatus.APPROVED, 3));
        Balance b = LeaveBalanceCalculator.balanceFor(CASUAL, "Casual", 10, rs, YEAR);
        assertEquals(3, b.taken());
        assertEquals(7, b.remaining());
    }

    @Test
    @DisplayName("an uncapped type reports NULL remaining, never zero")
    void uncapped_remaining_is_null() {
        // Zero would read as "none left" on the screen — the opposite of the truth for unpaid leave.
        Balance b = LeaveBalanceCalculator.balanceFor(UNPAID, "Unpaid", null, List.of(), YEAR);
        assertNull(b.remaining());
        assertNull(b.quota());
    }

    @Test
    @DisplayName("a balance can go negative, and says so rather than clamping")
    void balance_may_be_negative() {
        List<LeaveRequest> rs = List.of(
                req(CASUAL, "2026-03-02", "2026-03-13", LeaveRequestStatus.APPROVED, 12));
        Balance b = LeaveBalanceCalculator.balanceFor(CASUAL, "Casual", 10, rs, YEAR);
        assertEquals(-2, b.remaining(), "clamping at 0 would hide an overage the head approved");
    }

    @Test
    @DisplayName("overage is what a request exceeds by, and 0 when it fits")
    void overage() {
        assertEquals(3, LeaveBalanceCalculator.overageFor(10, 8, 5));
        assertEquals(0, LeaveBalanceCalculator.overageFor(10, 8, 2), "exactly using the balance is not over");
        assertEquals(0, LeaveBalanceCalculator.overageFor(10, 0, 1));
        assertEquals(0, LeaveBalanceCalculator.overageFor(null, 99, 99), "an uncapped type is never over");
    }
}
