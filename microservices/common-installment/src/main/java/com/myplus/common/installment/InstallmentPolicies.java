package com.myplus.common.installment;

/**
 * The setting VALUES this library understands, as constants.
 *
 * <h3>Why these are constants and not just strings in the catalog</h3>
 * {@code CreditLimitPolicy} established the rule: the settings catalog that OFFERS a choice and the code that
 * READS it are in different modules, and a typo in either is invisible until a tenant changes the setting and
 * nothing happens. Sharing the literal removes the possibility.
 *
 * <h3>⚠ Every value here is lowercase, and that is load-bearing</h3>
 * {@code SettingsService.getChoice(key, allowed, fallback)} does
 * {@code v.trim().toLowerCase(Locale.ROOT)} and then returns {@code fallback} unless the {@code allowed} set
 * contains the result. An uppercase option value can therefore NEVER match: the owner changes the setting, it
 * saves, and the behaviour silently stays on the default forever — no error, no log, nothing to see.
 *
 * <p>The catalog contains both conventions today, and they are not interchangeable:
 * {@code pos.sale.creditLimitPolicy} uses {@code off/warn/block} and is read through {@code getChoice} (works);
 * {@code pos.entry.preset} uses {@code CUSTOM/RETAIL/...} and is read in JavaScript through
 * {@code posSettingText}, never through {@code getChoice}. Everything in this file is read through
 * {@code getChoice}, so everything in this file is lowercase.
 */
public final class InstallmentPolicies {

    private InstallmentPolicies() {
    }

    // ---- pos.installment.frequency ------------------------------------------------------------------------
    // NOT redefined here. The frequency strings belong to the enum that names them
    // ({@link Frequency.SettingValue}), and a second copy in this file would be exactly the drift these
    // constants exist to prevent. Only the allowed SET is assembled here, from the enum itself, so adding a
    // frequency cannot leave the catalog and the reader disagreeing.

    // ---- pos.installment.allocationOrder ------------------------------------------------------------------
    /** Merge installments and ordinary invoices on date — the accountant's answer, and the default. */
    public static final String ALLOC_BY_DUE_DATE = "by-due-date";
    /** Clear the plan first: a shop chasing a financed handset wants the money on the plan. */
    public static final String ALLOC_INSTALLMENTS_FIRST = "installments-first";
    /** Clear ordinary paper first: a shop closing its month wants the oldest invoice gone. */
    public static final String ALLOC_INVOICES_FIRST = "invoices-first";

    // ---- pos.installment.lateFee.policy -------------------------------------------------------------------
    public static final String LATE_FEE_OFF = "off";
    public static final String LATE_FEE_FLAT = "flat";
    public static final String LATE_FEE_PERCENT = "percent";

    // ---- pos.installment.reminder.channel -----------------------------------------------------------------
    public static final String CHANNEL_EMAIL = "email";
    public static final String CHANNEL_SMS = "sms";
    public static final String CHANNEL_BOTH = "both";

    // ---- allowed sets, for SettingsService.getChoice ------------------------------------------------------

    /** Derived from the enum, so a new {@link Frequency} is offerable without touching this file. */
    public static final java.util.Set<String> FREQUENCIES =
            java.util.Arrays.stream(Frequency.values())
                    .map(Frequency::settingValue)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    public static final java.util.Set<String> ALLOCATION_ORDERS =
            java.util.Set.of(ALLOC_BY_DUE_DATE, ALLOC_INSTALLMENTS_FIRST, ALLOC_INVOICES_FIRST);
    public static final java.util.Set<String> LATE_FEE_POLICIES =
            java.util.Set.of(LATE_FEE_OFF, LATE_FEE_FLAT, LATE_FEE_PERCENT);
    public static final java.util.Set<String> CHANNELS =
            java.util.Set.of(CHANNEL_EMAIL, CHANNEL_SMS, CHANNEL_BOTH);
}
