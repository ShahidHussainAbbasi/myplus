package com.myplus.clinical.service;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ValidationException;

/**
 * Who may do what, in one place.
 *
 * <h3>The capability check fails CLOSED, on reads too</h3>
 * The clinic is an opt-in module. Unlike most modules, a READ is refused without it as well: these rows are
 * patients, and "the module is off" must mean no patient data leaves the service. An unresolved capability set
 * (null) is refused too.
 *
 * <h3>Tiers (S1)</h3>
 * Every member of the clinic may register and find patients (the front desk). Dedicated RECEPTION / DOCTOR /
 * PHARMACIST privileges arrive with the doctor's workspace (S3), which is the first screen holding clinical data.
 */
@Component
public class ClinicAccess {

    public static final String CAPABILITY = "clinic";

    /** H2 — the doctor a login IS (read per call; one indexed row, uq_provider_user). */
    private final com.myplus.clinical.repository.ClinicProviderRepo providers;

    public ClinicAccess(com.myplus.clinical.repository.ClinicProviderRepo providers) {
        this.providers = providers;
    }

    public Long org() {
        Long org = CurrentUser.organizationId();
        if (org == null) throw new ValidationException("No active organisation.");
        return org;
    }

    public Long userId() { return CurrentUser.userId(); }

    /** S3a — the permission that opens clinical records (auth-service V20; the owner holds it; the "Doctor" set grants it). */
    public static final String CONSULT = "clinic.consult";

    private static boolean holds(String authority) {
        return CurrentUser.get().map(u -> u.getAuthorities() != null && u.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()))).orElse(false);
    }

    /**
     * H2 — the clinic's doctor this login IS, if the owner / an admin linked it. Empty for the owner, the front desk,
     * and Doctor-set members who are not linked.
     */
    public java.util.Optional<Long> linkedProviderId() {
        Long org = CurrentUser.organizationId(), user = CurrentUser.userId();
        if (org == null || user == null) return java.util.Optional.empty();
        return providers.findByOrganizationIdAndUserId(org, user).map(com.myplus.clinical.entity.ClinicProvider::getProviderId);
    }

    /**
     * S3a (M-15) — only a doctor reads or writes the clinical record and moves a patient through the consultation.
     * H2: "a doctor" is the Doctor permission (the owner holds it; the Doctor set grants it) OR a login the owner / an
     * admin LINKED to a doctor of this clinic — so an admin can register a doctor without assigning sets (owner-only).
     */
    public void assertCanConsult() {
        boolean ok = holds(CONSULT) || linkedProviderId().isPresent();
        if (!ok) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only a doctor can open clinical records. The owner can put you on the Doctor permission set.");
        }
    }

    /**
     * H2 (L-2) — a LINKED doctor works only their own queue and visits. The owner (linked or not) and Doctor-set
     * members without a link keep every queue (they cover for a doctor who is away).
     */
    public void assertMayWorkProvider(Long providerId, String doctorName) {
        // the OWNER is never limited: an owner who is also a doctor (linked, so My queue opens on them) still covers
        // every queue — found by the S2 gate after an owner linked their own login (2026-10-10)
        if (holds("ROLE_OWNER")) return;
        java.util.Optional<Long> mine = linkedProviderId();
        if (mine.isPresent() && !mine.get().equals(providerId)) {
            throw new org.springframework.security.access.AccessDeniedException("This is "
                    + (doctorName == null || doctorName.isBlank() ? "another doctor" : doctorName)
                    + "'s patient. You see your own queue.");
        }
    }

    public boolean isOwner() { return holds("ROLE_OWNER"); }

    // ── RW-1 (2026-10-12): the clinic's own permissions (auth-service V23; docs/hms-role-workspaces-design.md §3.2) ──
    public static final String PATIENT_VIEW = "clinic.patient.view";
    public static final String PATIENT_EDIT = "clinic.patient.edit";
    public static final String QUEUE_VIEW = "clinic.queue.view";
    public static final String TOKEN_ISSUE = "clinic.token.issue";
    public static final String TOKEN_CANCEL = "clinic.token.cancel";
    public static final String DOCTOR_DAY = "clinic.doctor.day";
    public static final String DOCTOR_MANAGE = "clinic.doctor.manage";

    /** The owner and an administrator keep the whole clinic, whatever set they are on (they did before RW-1). */
    private static boolean wholeClinic() { return holds("ROLE_OWNER") || holds("ADMIN_ROLE"); }

    private static void refuse(String what) {
        throw new org.springframework.security.access.AccessDeniedException(
                "Your role does not include " + what + ". The owner can change your permission set on Team.");
    }

    /** RW-1 — find a patient: the front desk, the pharmacist, and whoever may consult (a doctor reads their patient). */
    public void assertMayViewPatients() {
        if (wholeClinic() || holds(PATIENT_VIEW) || holds(CONSULT) || linkedProviderId().isPresent()) return;
        refuse("finding patients");
    }

    /** RW-1 — register or correct a patient: the front desk. */
    public void assertMayEditPatients() {
        if (wholeClinic() || holds(PATIENT_EDIT)) return;
        refuse("registering or correcting patients");
    }

    /** RW-1 — today's queue: the front desk, and a doctor for their own line (My queue). */
    public void assertMayViewQueue() {
        if (wholeClinic() || holds(QUEUE_VIEW) || holds(CONSULT) || linkedProviderId().isPresent()) return;
        refuse("today's queue");
    }

    /** RW-1 — give a token, or move it to another doctor: the front desk. */
    public void assertMayIssueTokens() {
        if (wholeClinic() || holds(TOKEN_ISSUE)) return;
        refuse("giving tokens");
    }

    /** RW-1 — cancel a token or mark the patient not here: the front desk. */
    public void assertMayCancelTokens() {
        if (wholeClinic() || holds(TOKEN_CANCEL)) return;
        refuse("cancelling tokens");
    }

    /** RW-1 (D2) — a doctor's day (patients a day, not today): the front desk and the administration. */
    public void assertMaySetDoctorDay() {
        if (wholeClinic() || holds(DOCTOR_DAY)) return;
        refuse("setting a doctor's day");
    }

    /**
     * H2 — registering and linking doctors is the owner's or an admin's (the team-management rule in auth-service).
     * RW-1: also a member whose set holds {@code clinic.doctor.manage}.
     */
    public void assertClinicAdmin() {
        if (!(wholeClinic() || holds(DOCTOR_MANAGE))) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only the owner or an admin can register doctors.");
        }
    }

    public void assertModuleOn() {
        Set<String> caps = CurrentUser.capabilities();
        if (caps == null || !caps.contains(CAPABILITY)) {
            throw new ValidationException("The clinic is not switched on for this business. "
                    + "An owner can switch it on in Settings → Configuration.");
        }
    }
}
