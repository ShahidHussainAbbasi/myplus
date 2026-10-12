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
 *   <li>{@code rx.dispense} and the "Pharmacist" set: only where prescriptions are dispensed (prescriptions on, or the
 *       clinic on) — RW-1 (2026-10-12).</li>
 * </ul>
 * The capability is read from {@code CapabilityService} (settings + entitlements), never from the caller's token.
 */
public final class PermissionVisibility {

    private PermissionVisibility() {}

    public static final Set<String> CLINIC_SETS = Set.of("Doctor", "Clinic reception");
    /** RW-1: a pharmacy's sets — offered where prescriptions are dispensed. */
    public static final Set<String> PHARMACY_SETS = Set.of("Pharmacist");
    public static final String RX_DISPENSE = "rx.dispense";

    /** RW-2 — the sign-in marker for a business member on NO permission set (their menus stay as before sets). */
    public static final String NO_PERMISSION_SET = "NO_PERMISSION_SET";

    /**
     * RW-1 — the front desk's clinic codes. Before V23 any member could do these once the clinic was on; a member on NO
     * set keeps them (AuthService.buildClaims), exactly as V22 kept dispensing for them.
     */
    public static final java.util.List<String> CLINIC_DESK = java.util.List.of(
            "clinic.patient.view", "clinic.patient.edit", "clinic.queue.view",
            "clinic.token.issue", "clinic.token.cancel", "clinic.doctor.day");

    public static boolean codeVisible(String code, boolean clinicOn, boolean rxOn) {
        if (code == null) return false;
        if (code.startsWith("clinic.")) return clinicOn;
        if (RX_DISPENSE.equals(code)) return rxOn || clinicOn;
        return true;
    }

    public static boolean setVisible(String name, boolean builtin, boolean clinicOn) {
        return setVisible(name, builtin, clinicOn, false);
    }

    public static boolean setVisible(String name, boolean builtin, boolean clinicOn, boolean rxOn) {
        if (!builtin) return true;
        if (CLINIC_SETS.contains(name)) return clinicOn;
        if (PHARMACY_SETS.contains(name)) return clinicOn || rxOn;
        return true;
    }
}
