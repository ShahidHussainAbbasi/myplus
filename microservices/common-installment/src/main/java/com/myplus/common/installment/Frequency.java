package com.myplus.common.installment;

import java.time.LocalDate;
import java.util.Locale;

/**
 * How often an installment falls due.
 *
 * <h3>The setting values are LOWERCASE, and that is load-bearing</h3>
 * {@code SettingsService.getChoice} lower-cases the stored value before matching it against the allowed set,
 * and <b>silently returns the fallback</b> when it does not match. So a catalog entry offering {@code MONTHLY}
 * would be saved by the owner, read back as the default forever, and report no error anywhere.
 *
 * <p>The constants therefore live here, beside the enum they name, exactly as {@code CreditLimitPolicy.OFF /
 * WARN / BLOCK} do — so the catalog and the reader cannot drift apart.
 *
 * <h3>Date arithmetic is calendar-aware, never {@code plusDays(30)}</h3>
 * {@code MONTHLY} from the 31st lands on the 30th, then the 28th, because {@link LocalDate#plusMonths} clamps
 * to the month's length. Adding 30 days instead would walk the due date backwards through the calendar — a
 * plan taken on the 31st of January would fall due on the 2nd of March, and every month after that would
 * drift further from the date the customer agreed to.
 */
public enum Frequency {

    WEEKLY(SettingValue.WEEKLY) {
        @Override public LocalDate advance(LocalDate from, int periods) { return from.plusWeeks(periods); }
    },

    FORTNIGHTLY(SettingValue.FORTNIGHTLY) {
        @Override public LocalDate advance(LocalDate from, int periods) { return from.plusWeeks(2L * periods); }
    },

    MONTHLY(SettingValue.MONTHLY) {
        @Override public LocalDate advance(LocalDate from, int periods) { return from.plusMonths(periods); }
    };

    /** The lowercase strings the settings catalog offers and {@code getChoice} will match. */
    public static final class SettingValue {
        public static final String WEEKLY = "weekly";
        public static final String FORTNIGHTLY = "fortnightly";
        public static final String MONTHLY = "monthly";

        private SettingValue() {}
    }

    private final String settingValue;

    Frequency(String settingValue) {
        this.settingValue = settingValue;
    }

    /** The lowercase value stored in {@code org_setting} for this frequency. */
    public String settingValue() {
        return settingValue;
    }

    /** {@code from} advanced by {@code periods} of this frequency. */
    public abstract LocalDate advance(LocalDate from, int periods);

    /**
     * Resolve a stored setting value, tolerating case and whitespace.
     *
     * @return the matching frequency, or {@code MONTHLY} when the value is absent or unrecognised — the same
     *         fail-safe {@code getChoice} applies, stated here so a caller reading the column directly cannot
     *         behave differently from one reading it through the settings service.
     */
    public static Frequency fromSetting(String value) {
        if (value != null) {
            String norm = value.trim().toLowerCase(Locale.ROOT);
            for (Frequency f : values()) {
                if (f.settingValue.equals(norm) || f.name().toLowerCase(Locale.ROOT).equals(norm)) return f;
            }
        }
        return MONTHLY;
    }
}
