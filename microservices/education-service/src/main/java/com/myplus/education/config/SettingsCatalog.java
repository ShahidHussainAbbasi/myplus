package com.myplus.education.config;

import java.util.List;

/**
 * The single source of truth for WHAT an owner can configure — a code-defined catalog. Each entry declares a
 * key, a human label, a type, a default, and a UI group. The Configuration screen renders itself from this;
 * the per-tenant {@code org_setting} table stores only the overrides. Adding a new configurable policy is one
 * entry here + one read call in the behaviour that honours it — NO schema change.
 *
 * Kept deliberately small and explicit (a list, not annotations/reflection) so the set of tenant-configurable
 * behaviour is greppable in one place.
 */
public final class SettingsCatalog {

    private SettingsCatalog() { }

    public enum Type { BOOL, INT, TEXT }

    public record Entry(String key, String label, String help, Type type, String defaultValue, String group) { }

    // ── Registered settings ──────────────────────────────────────────────────────────────────────────
    // Branch-policy toggles default OFF = org-wide (a single-branch school never needs them; a group can opt in).
    public static final List<Entry> ENTRIES = List.of(
            new Entry("edu.guardian.branchScoped",
                    "Restrict guardians to their branch",
                    "Off (default): guardians are visible org-wide (a parent may have children at more than one campus). "
                            + "On: a branch's staff see only guardians who have a student at that branch.",
                    Type.BOOL, "false", "Branch policy"),
            new Entry("edu.discount.branchScoped",
                    "Restrict discounts to their branch",
                    "Off (default): fee discounts are visible org-wide. On: a branch sees only discounts applied to "
                            + "a student at that branch.",
                    Type.BOOL, "false", "Branch policy")
    );

    public static Entry byKey(String key) {
        return ENTRIES.stream().filter(e -> e.key().equals(key)).findFirst().orElse(null);
    }
}
