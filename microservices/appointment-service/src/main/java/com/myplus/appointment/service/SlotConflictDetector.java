package com.myplus.appointment.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Slice SCHED-1 (B2) — the slot arithmetic, as pure functions.
 *
 * <h3>Why pure, and why tested before anything calls it</h3>
 *
 * The same treatment given to {@code ClashDetector} (education 2.1), {@code LeaveBalanceCalculator} (2.3)
 * and {@code NoticeAudienceResolver} (3.5): the checks with the highest consequence are the ones that must
 * be readable and testable in isolation, with no Spring, no database and no clock.
 *
 * <h3>What this is NOT</h3>
 *
 * <b>It is not the guarantee.</b> The guarantee is
 * {@code uk_slot_provider_time (organization_id, provider_id, starts_at)} in V4 — a constraint holds under
 * concurrency and a method call does not. This class exists to give a caller a useful ANSWER before the
 * constraint gives it a blunt one, which is the same division of labour the booking queue now uses.
 *
 * <p>Stated plainly because this codebase has twelve open check-then-act races that were written the other
 * way round (education finding D): the check came first and no constraint ever followed.
 */
public final class SlotConflictDetector {

    private SlotConflictDetector() {
    }

    /**
     * PURE. Do two time windows overlap?
     *
     * <p><b>Half-open intervals: [start, end).</b> 10:00–10:10 and 10:10–10:20 do NOT overlap, which is the
     * single most important case here — back-to-back slots are what a parents' evening is made of, and an
     * inclusive comparison would refuse every consecutive pair as a clash.
     *
     * <p>A null bound, or a window that ends before it starts, is not an overlap: nonsense input must not
     * become a permissive answer.
     */
    public static boolean overlaps(LocalDateTime aStart, LocalDateTime aEnd,
                                   LocalDateTime bStart, LocalDateTime bEnd) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) return false;
        if (!aEnd.isAfter(aStart) || !bEnd.isAfter(bStart)) return false;   // zero-length or inverted
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    /**
     * PURE. Cut a window into consecutive slots of {@code minutes} each.
     *
     * <p><b>A slot that would run past the end is not generated</b> — six-and-a-bit ten-minute slots in an
     * hour is six, not seven. A school setting up "18:00–19:00, 10 minutes" gets exactly what it asked for,
     * and never one slot that finishes after the evening does.
     *
     * <p>Returns an empty list rather than throwing on nonsense input (null bounds, a non-positive length,
     * an inverted window): generation is driven by a form, and a form typo should show an empty grid the
     * user can correct, not a stack trace.
     */
    public static List<Window> generate(LocalDateTime from, LocalDateTime to, int minutes) {
        List<Window> out = new ArrayList<>();
        if (from == null || to == null || minutes <= 0 || !to.isAfter(from)) return out;

        LocalDateTime cursor = from;
        while (true) {
            LocalDateTime end = cursor.plus(Duration.ofMinutes(minutes));
            if (end.isAfter(to)) break;      // the partial tail is dropped, deliberately
            out.add(new Window(cursor, end));
            cursor = end;
        }
        return out;
    }

    /** One generated slot window. */
    public record Window(LocalDateTime startsAt, LocalDateTime endsAt) { }
}
