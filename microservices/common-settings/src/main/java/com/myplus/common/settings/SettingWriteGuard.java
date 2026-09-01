package com.myplus.common.settings;

/**
 * E1 — a rule that may refuse a settings WRITE before it happens.
 *
 * <h3>Why a chain and not an {@code if} in {@code set()}</h3>
 * Chain of Responsibility, so the next rule — a quota guard, a compliance guard, a "this policy is frozen
 * during a period close" guard — adds a bean rather than a branch inside {@link SettingsService#set}. That
 * method is on the write path of every settings screen in the platform; it should not accumulate the reasons.
 *
 * <h3>Refuse by throwing, and throw the type the API already understands</h3>
 * {@link IllegalArgumentException} is what {@code SettingsService.set} already throws for an unknown key and
 * what {@code SettingsController} already turns into a 400-style {@code ApiResponse.error} — which reaches the
 * owner as <b>HTTP 200 with {@code success:false}</b> and the guard's own sentence in {@code message}. Adding a
 * new exception type here would mean a second refusal shape for the same kind of answer.
 *
 * <h3>The message is read by a person</h3>
 * It is rendered on the Configuration screen verbatim (standard 8d — the server's sentence wins). So it says
 * what cannot be done and why, in the owner's terms, and never names the settings key: a message that describes
 * the configuration namespace is an information disclosure to anyone probing endpoints, the same rule the
 * anti-IDOR reads follow.
 */
@FunctionalInterface
public interface SettingWriteGuard {

    /**
     * Throw to refuse the write; return normally to allow it.
     *
     * <p>Runs BEFORE the upsert and inside the caller's transaction, so a refusal cannot leave a half-applied
     * state and cannot be observed by a concurrent reader.
     *
     * @param organizationId the tenant the write is for
     * @param key            the settings key, already known to be in the catalog
     * @param value          the value being written, as it will be stored
     * @throws IllegalArgumentException with an owner-facing sentence when the write must not proceed
     */
    void check(Long organizationId, String key, String value);
}
