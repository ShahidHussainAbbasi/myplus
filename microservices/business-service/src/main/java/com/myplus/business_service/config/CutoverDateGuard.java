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
    @Autowired
    public CutoverDateGuard(@Lazy SettingsService settings) {
        this.settings = settings;
    }

    @Override
    public void check(Long organizationId, String key, String value) {
        if (!OpeningBalanceService.CUTOVER_KEY.equals(key)) return;   // not our key, not our business

        boolean locked = settings != null && settings.getBool(OpeningBalanceService.LOCKED_KEY);
        if (!locked) return;

        /*
         * CLEARING it is allowed, and deliberately so.
         *
         * The lock exists to stop the date MOVING under posted documents. A tenant that has reversed every
         * opening balance has nothing anchored to it any more, and refusing to let them start again would
         * strand a shop that mis-typed the date on its first attempt — turning a correctable mistake into a
         * support call. The lock is released alongside, by whoever clears it.
         */
        if (value == null || value.trim().isEmpty()) return;

        throw new IllegalArgumentException(
                "Opening balances have already been recorded against the current date, so it can no longer "
                        + "be changed — those entries are in the accounts and moving the date would silently "
                        + "re-date them. Reverse the opening balances first if the date was wrong.");
    }
}
