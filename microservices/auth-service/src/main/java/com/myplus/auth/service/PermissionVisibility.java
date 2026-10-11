package com.myplus.auth.service;

import java.util.Set;

/**
 * HMS H3a (client ruling L-3) — what the Team screen offers a business, by what it has switched on.
 *
 * <p>The built-in sets and codes are global (organisation NULL), so a shop with no clinic used to see "Doctor" on its
 * Team screen. Harmless (clinical-service refuses everything without the clinic) but confusing, and an owner could
 * assign a set that grants nothing. Hidden here; the rows themselves are untouched.
 *
 * <ul>
 *   <li>clinic codes ({@code clinic.*}) and the clinic sets ("Doctor", "Clinic reception"): only with the clinic on;</li>
 *   <li>{@code rx.dispense}: only where prescriptions are dispensed (prescriptions on, or the clinic on).</li>
 * </ul>
 * The capability is read from {@code CapabilityService} (settings + entitlements), never from the caller's token.
 */
public final class PermissionVisibility {

    private PermissionVisibility() {}

    public static final Set<String> CLINIC_SETS = Set.of("Doctor", "Clinic reception");
    public static final String RX_DISPENSE = "rx.dispense";

    public static boolean codeVisible(String code, boolean clinicOn, boolean rxOn) {
        if (code == null) return false;
        if (code.startsWith("clinic.")) return clinicOn;
        if (RX_DISPENSE.equals(code)) return rxOn || clinicOn;
        return true;
    }

    public static boolean setVisible(String name, boolean builtin, boolean clinicOn) {
        return !(builtin && CLINIC_SETS.contains(name)) || clinicOn;
    }
}
