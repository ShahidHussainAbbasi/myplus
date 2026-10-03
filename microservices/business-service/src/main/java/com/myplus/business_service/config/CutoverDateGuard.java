package com.myplus.business_service.config;

import com.myplus.business_service.service.OpeningBalanceService;
import com.myplus.common.settings.SettingWriteGuard;
import com.myplus.common.settings.SettingsService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * OB-1 / Q3 — the cutover date cannot move once opening balances have been posted against it.
 *
 * <h3>Why a guard bean rather than a check inside the endpoint</h3>
 * The cutover date is an ordinary tenant setting, so it can be written from the Configuration screen, the
 * settings API, or any future importer — three doors, and a check inside one of them protects one of them.
 * {@link SettingWriteGuard} is the platform's Chain-of-Responsibility extension point for exactly this: the
 * rule attaches to the KEY, wherever the write comes from.
 *
 * <h3>What is actually being protected</h3>
 * Every opening document is DATED by the cutover. Moving it afterwards would silently re-date entries already
 * sitting in the general ledger — the same class of change period close exists to prevent, and one that no
 * screen would show: the customer balance would be unchanged, the aging quietly wrong, and the reconciliation
 * against the shop's old system would fail months later with no obvious cause.
 *
 * <p>⚠ <b>The message must say what to do, not merely refuse.</b> A shop that genuinely picked the wrong date
 * needs a route, and until OB-3 ships the honest answer is that reversing the balances releases the lock —
 * because with nothing posted, nothing is anchored.
 */
@Component
public class CutoverDateGuard implements SettingWriteGuard {

    private final SettingsService settings;

    /**
     * {@code @Lazy} is kept, and the reason is NOT the cycle it looks like.
     *
     * <p>There is no construction cycle: {@code SettingsService} collects guards through an
     * {@code ObjectProvider}, which resolves them on demand rather than at construction — verified in that
     * class rather than assumed, because the obvious reading is wrong and would have put a false claim in
     * this comment. The service is therefore fully built before any guard is.
     *
     * <p>What the lazy proxy actually buys is order-independence: this guard is in business-service while
     * the service it reads lives in a shared library, and a future refactor that made guard collection eager
     * would break startup rather than a test. One annotation against a whole class of boot failure.
     */
    private final com.myplus.business_service.repository.CustomerHistoryRepo customerHistoryRepo;
    private final com.myplus.business_service.repository.PurchaseRepo purchaseRepo;

    @Autowired
    public CutoverDateGuard(@Lazy SettingsService settings,
                            com.myplus.business_service.repository.CustomerHistoryRepo customerHistoryRepo,
                            com.myplus.business_service.repository.PurchaseRepo purchaseRepo) {
        this.settings = settings;
        this.customerHistoryRepo = customerHistoryRepo;
        this.purchaseRepo = purchaseRepo;
    }

    /**
     * SET-GUIDE / OB-1 — the lock is a FACT about the books, not a preference.
     *
     * <h3>What it replaces</h3>
     * The guard trusted the {@code cutoverLocked} switch, and let a locked date be CLEARED on the assumption that
     * clearing only happens after every balance is reversed — never checked. Both holes were reproduced 2026-10-02:
     * the switch could simply be turned off, and a blank save was accepted while balances stood; the business was
     * then left with no date at all, so every later opening balance was refused.
     *
     * <h3>The rule (as SAP's posting periods, Odoo's lock dates and Tally's books-beginning date work)</h3>
     * While any opening balance still STANDS (not reversed), the cutover date may only be the date those balances
     * are dated — every move and every clear is refused, and the lock cannot come off. With none standing, both are
     * free: reversing the balances is the documented way back, and it now really is one. A business already left
     * blank by the old hole can be repaired to the right date — the only value this accepts.
     *
     * <p>Reset to default reaches here too ({@code SettingWriteGuard.checkReset} judges the default, i.e. blank /
     * false), so the one-click reset is held to the same rule.
     */
    @Override
    public void check(Long organizationId, String key, String value) {
        boolean lockKey = OpeningBalanceService.LOCKED_KEY.equals(key);
        if (!lockKey && !OpeningBalanceService.CUTOVER_KEY.equals(key)) return;   // not our keys

        java.util.Set<java.time.LocalDate> anchored = standingOpeningDates(organizationId);
        if (anchored.isEmpty()) return;   // nothing posted stands on the date: it may change, and the lock may come off

        if (lockKey) {
            boolean unlocking = value == null || !"true".equalsIgnoreCase(value.trim());
            if (unlocking) {
                throw new IllegalArgumentException(
                        "Opening balances are recorded against the cutover date, so the lock cannot come off — "
                                + "moving the date would re-date entries already in the accounts. Reverse the "
                                + "opening balances first; the lock can then be switched off.");
            }
            return;
        }

        java.time.LocalDate wanted = parse(value);
        if (wanted != null && anchored.contains(wanted)) return;   // the date they are dated by: a no-op, or a repair
        throw new IllegalArgumentException(
                "Opening balances have already been recorded against " + describe(anchored) + ", so the cutover "
                        + "date can no longer be changed or cleared — those entries are in the accounts and moving the "
                        + "date would silently re-date them. Reverse the opening balances first if the date was wrong.");
    }

    /** The cutover dates that standing opening balances (customer and supplier) are dated by. */
    private java.util.Set<java.time.LocalDate> standingOpeningDates(Long organizationId) {
        java.util.Set<java.time.LocalDate> out = new java.util.TreeSet<>();
        if (organizationId == null) return out;
        for (java.time.LocalDateTime d : customerHistoryRepo.standingOpeningDates(organizationId)) if (d != null) out.add(d.toLocalDate());
        for (java.time.LocalDateTime d : purchaseRepo.standingOpeningDates(organizationId)) if (d != null) out.add(d.toLocalDate());
        return out;
    }

    private static java.time.LocalDate parse(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try { return java.time.LocalDate.parse(value.trim()); } catch (Exception notADate) { return null; }
    }

    private static String describe(java.util.Set<java.time.LocalDate> dates) {
        return dates.size() == 1 ? dates.iterator().next().toString() : "the dates " + dates;
    }
}
