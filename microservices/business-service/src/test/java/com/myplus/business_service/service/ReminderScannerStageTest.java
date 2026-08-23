package com.myplus.business_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.business_service.entity.InstallmentReminder;

/**
 * INST-3a — the scanner's only piece of arithmetic, tested with no container.
 *
 * <p>Runs on {@code mvn test} with nothing running: {@code stageFor} is deliberately {@code static} and takes
 * its dates as parameters rather than reading a clock, so the boundary cases below are ordinary assertions
 * instead of a Testcontainers run that <b>skips silently on this machine</b> (docker-java negotiates API 1.32
 * against an engine requiring 1.40, and a skipped test is indistinguishable from a passing one).
 *
 * <p>The boundaries matter more than they look. One day out in either direction tells a shop to make a
 * collection call to a customer who has until close of business — the sort of defect that costs goodwill
 * rather than money, and that no integration test would have caught because both answers look reasonable.
 */
class ReminderScannerStageTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);

    /** beforeDays = 3, so the courtesy window runs through the 24th. */
    private static final LocalDate SOON_THROUGH = TODAY.plusDays(3);

    private String stage(LocalDate due) {
        return ReminderScanner.stageFor(due, TODAY, SOON_THROUGH);
    }

    @Test
    @DisplayName("yesterday is a collection call")
    void overdue() {
        assertEquals(InstallmentReminder.STAGE_OVERDUE, stage(TODAY.minusDays(1)));
        assertEquals(InstallmentReminder.STAGE_OVERDUE, stage(TODAY.minusMonths(2)));
    }

    @Test
    @DisplayName("⭐ due TODAY is NOT yet overdue — the customer has until close of business")
    void dueTodayIsNotOverdue() {
        // The same boundary INST-1 set for the derived overdue predicate. If these two ever disagree, the
        // Installments screen and the worklist describe the same customer differently on the same morning.
        assertEquals(InstallmentReminder.STAGE_DUE_SOON, stage(TODAY));
    }

    @Test
    @DisplayName("the last day of the courtesy window is included")
    void inclusiveUpperBound() {
        assertEquals(InstallmentReminder.STAGE_DUE_SOON, stage(SOON_THROUGH));
    }

    @Test
    @DisplayName("the day after the window is not chased at all")
    void beyondWindow() {
        // The negative control for the case above. Without it, "due soon" would be satisfied by a scanner
        // that chases every future instalment on every plan the day it is sold.
        assertNull(stage(SOON_THROUGH.plusDays(1)));
        assertNull(stage(TODAY.plusMonths(5)));
    }

    @Test
    @DisplayName("beforeDays = 0 lists nothing until a payment is actually late")
    void zeroWindow() {
        LocalDate noWindow = TODAY;   // today.plusDays(0)
        assertEquals(InstallmentReminder.STAGE_DUE_SOON,
                ReminderScanner.stageFor(TODAY, TODAY, noWindow));
        assertNull(ReminderScanner.stageFor(TODAY.plusDays(1), TODAY, noWindow));
        assertEquals(InstallmentReminder.STAGE_OVERDUE,
                ReminderScanner.stageFor(TODAY.minusDays(1), TODAY, noWindow));
    }

    @Test
    @DisplayName("the dedupe key contains the stage, so re-defaulting cannot chase twice")
    void dedupeKeyCarriesStage() {
        String overdue = InstallmentReminder.keyFor("PLN-0007", 3, InstallmentReminder.STAGE_OVERDUE);
        String soon = InstallmentReminder.keyFor("PLN-0007", 3, InstallmentReminder.STAGE_DUE_SOON);

        // Two stages for one instalment are two rows; the same stage twice is one row, whatever day the
        // scanner runs. The absence of a date in this string is the whole point of it.
        assertEquals("INST/PLN-0007/3/OVERDUE", overdue);
        assertEquals("INST/PLN-0007/3/DUE_SOON", soon);
        assertEquals(overdue, InstallmentReminder.keyFor("PLN-0007", 3, InstallmentReminder.STAGE_OVERDUE));

        // And it fits the column it has to survive in — 120 chars, matching notification_broadcast.dedupe_key
        // so INST-3b can pass it straight through.
        org.junit.jupiter.api.Assertions.assertTrue(overdue.length() <= 120);
    }
}
