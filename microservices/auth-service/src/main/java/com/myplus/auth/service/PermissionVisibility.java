package com.myplus.auth.service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the Team screen offers a business: the codes and sets whose required setting is on.
 *
 * <p>RW-0a (2026-10-12) — <b>the rule is data</b>. Which setting a code or set needs is the {@code capability} column
 * of {@code permission} / {@code permission_set} (V24): empty = always offered; one or more Capability codes,
 * comma-separated, = offered when ANY of them is on. Until V24 this class named the sets ("Doctor", "Clinic reception",
 * "Pharmacist") and the codes ("clinic.*", "rx.dispense") itself; a new set or code now needs a row, not a release.
 *
 * <p>Hiding is about relevance (a shop without a clinic is not offered "Doctor"), not security — the services refuse
 * on their own. The capability is read from {@code CapabilityService} (settings + entitlements), never the token.
 */
public final class PermissionVisibility {

    private PermissionVisibility() {}

    /** The capability codes switched on, from {@code CapabilityService.enabledMapFor}. */
    public static Set<String> enabled(Map<String, Boolean> enabledMap) {
        if (enabledMap == null) return Set.of();
        return enabledMap.entrySet().stream().filter(e -> Boolean.TRUE.equals(e.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * RW-3 — does a row whose {@code codes} column lists permissions (comma-separated = any) open for this person?
     * Empty = everyone; {@code everything} (the owner, an administrator) = always.
     */
    public static boolean opens(String codes, Set<String> held, boolean everything) {
        if (everything || codes == null || codes.isBlank()) return true;
        for (String c : codes.split(",")) {
            if (held != null && held.contains(c.trim())) return true;
        }
        return false;
    }

    /** Offered when its {@code capability} column is empty, or when ANY capability it lists is on. */
    public static boolean visible(String capability, Set<String> enabled) {
        if (capability == null || capability.isBlank()) return true;
        for (String c : capability.split(",")) {
            if (enabled != null && enabled.contains(c.trim())) return true;
        }
        return false;
    }

    /**
     * HC-1a — offered to these BUSINESS TYPES only: when {@code orgTypes} is empty the row is for every type; otherwise
     * the tenant's organization type must be one it lists (e.g. the Pharmacy and Stock tiles are not for a clinic).
     */
    public static boolean forOrgType(String orgTypes, String orgType) {
        if (orgTypes == null || orgTypes.isBlank()) return true;
        if (orgType == null) return false;
        for (String t : orgTypes.split(",")) {
            if (t.trim().equalsIgnoreCase(orgType)) return true;
        }
        return false;
    }
}
