package com.myplus.common.settings;

/**
 * E4 — a reaction to a settings write that has already happened.
 *
 * <h3>The symmetric twin of {@link SettingWriteGuard}, and the symmetry is the design</h3>
 * <table>
 *   <tr><th></th><th>{@code SettingWriteGuard} (E1)</th><th>{@code SettingWriteListener} (E4)</th></tr>
 *   <tr><td>Runs</td><td><b>before</b> the upsert</td><td><b>after</b> it has been applied</td></tr>
 *   <tr><td>May</td><td>refuse, by throwing</td><td>not refuse</td></tr>
 *   <tr><td>Injected as</td><td>{@code ObjectProvider}</td><td>{@code ObjectProvider}</td></tr>
 * </table>
 *
 * <p>A listener that could veto would be a guard wearing the wrong name, and a second refusal point is how two
 * places end up disagreeing about whether a write happened. So this one is told, not asked.
 *
 * <h3>Why a chain rather than an {@code if} in {@code set()}</h3>
 * Chain of Responsibility, exactly as the guard: the next cross-cutting reaction to a settings write — a
 * notification, a cache warm, a webhook — adds a bean instead of a branch inside {@link SettingsService#set},
 * which sits on the write path of every settings screen in the platform and should not accumulate reasons.
 *
 * <h3>⚠ Throwing from here rolls back the write it is reporting</h3>
 * It runs inside the caller's transaction, so an exception undoes the upsert — reporting a change by
 * preventing it. Implementations must be defensive: catch what they cannot handle, and let the settings
 * change stand. Auditing that can veto configuration is worse than auditing that misses a row.
 */
@FunctionalInterface
public interface SettingWriteListener {

    /**
     * Called once the value has been written, with both sides of the change.
     *
     * <p>{@code before} is the tenant's raw stored override as it was — {@code null} when there was none,
     * which is a real and interesting prior state (the tenant was on the shape preset) rather than a gap.
     *
     * @param organizationId the tenant whose setting changed
     * @param key            the settings key, already validated against the catalog
     * @param before         the previous stored value, or {@code null} if the tenant had no override
     * @param after          the value now stored
     */
    void applied(Long organizationId, String key, String before, String after);
}
