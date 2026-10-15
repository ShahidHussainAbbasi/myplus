package com.myplus.auth.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.auth.entity.OrgLink;
import com.myplus.auth.entity.OrgSetting;
import com.myplus.auth.entity.Organization;
import com.myplus.auth.repository.OrgLinkRepository;
import com.myplus.auth.repository.OrgSettingRepository;
import com.myplus.auth.repository.OrganizationRepository;

import lombok.RequiredArgsConstructor;

/**
 * HC-1c — a Hospital / Clinic LINKED to a pharmacy: two separate tenants, the first relationship in the platform that
 * crosses tenants. Design: microservices/docs/slices/hc-1cd-clinic-pharmacy-link.md (the user's rulings D1–D6).
 *
 * <h3>The rules, all here and none on the screen</h3>
 * <ul>
 *   <li>The acting organisation is ALWAYS the caller's own ({@code callerOrg}, read from the validated token by the
 *       controller) — no endpoint takes "my org" as a parameter.</li>
 *   <li>A pharmacy is found only if it switched {@link #ACCEPT_KEY} on, and only its id and NAME cross — never its
 *       stock, prices, customers or owner.</li>
 *   <li>Only the ADDRESSED pharmacy accepts; any other caller is told "not found" (anti-IDOR: a probe learns nothing,
 *       not even that the link exists).</li>
 *   <li>One REQUESTED-or-ACTIVE link per clinic (D1), serialised by a row lock on the clinic's organisation.</li>
 *   <li>Ending needs a reason; a row is never deleted or re-opened.</li>
 *   <li>Every step is written to BOTH businesses' audit trails.</li>
 * </ul>
 * Refusals are {@link LinkRefusedException} with a stable code, never a sentence the screen has to parse.
 */
@Service
@RequiredArgsConstructor
public class OrgLinkService {

    /** The pharmacy's opt-in: "Clinics may send us prescriptions". Off unless the row says {@code true}. */
    public static final String ACCEPT_KEY = "org.link.acceptClinics";
    static final String CLINIC = "CLINIC";
    static final String PHARMA = "PHARMA";
    static final int MAX_RESULTS = 20;

    private final OrgLinkRepository links;
    private final OrganizationRepository organizations;
    private final OrgSettingRepository settings;
    private final ControlPlaneAuditService audit;

    /** A link step refused for a stable reason — the controller answers it as {@code success:false, reason:<code>}. */
    public static class LinkRefusedException extends RuntimeException {
        private final String code;
        public LinkRefusedException(String code, String message) { super(message); this.code = code; }
        public String getCode() { return code; }
    }

    // ── the pharmacy's opt-in ─────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public boolean acceptsClinics(Long callerOrg) {
        requireType(callerOrg, PHARMA);
        return settings.findByOrganizationIdAndSettingKey(callerOrg, ACCEPT_KEY)
                .map(s -> "true".equalsIgnoreCase(s.getSettingValue())).orElse(false);
    }

    @Transactional
    public boolean setAcceptsClinics(Long callerOrg, Long userId, boolean on) {
        requireType(callerOrg, PHARMA);
        OrgSetting row = settings.findByOrganizationIdAndSettingKey(callerOrg, ACCEPT_KEY)
                .orElseGet(() -> OrgSetting.builder().organizationId(callerOrg).settingKey(ACCEPT_KEY).build());
        String before = row.getSettingValue();
        row.setSettingValue(String.valueOf(on));
        row.setUserId(userId);
        row.setUpdated(LocalDateTime.now());
        settings.save(row);
        audit.tenantAction("LINK_ACCEPT_CLINICS", "ORG_SETTING", ACCEPT_KEY, before, String.valueOf(on), null);
        return on;
    }

    // ── the clinic's side ─────────────────────────────────────────────────────────────────────────────────────

    /** Opted-in, ACTIVE pharmacies whose name contains {@code q} — id and name only. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findPharmacies(Long callerOrg, String q) {
        requireType(callerOrg, CLINIC);
        String needle = q == null ? "" : q.trim().toLowerCase();
        if (needle.length() < 2) throw new LinkRefusedException("LINK_QUERY_SHORT", "Type at least two letters of the pharmacy's name.");
        Set<Long> open = settings.findBySettingKeyAndSettingValue(ACCEPT_KEY, "true").stream()
                .map(OrgSetting::getOrganizationId).collect(Collectors.toSet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Organization o : organizations.findAllById(open)) {
            if (!PHARMA.equalsIgnoreCase(o.getType()) || !"ACTIVE".equalsIgnoreCase(o.getStatus())) continue;
            if (o.getName() == null || !o.getName().toLowerCase().contains(needle)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("name", o.getName());
            out.add(m);
            if (out.size() >= MAX_RESULTS) break;
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return out;
    }

    @Transactional
    public Map<String, Object> request(Long callerOrg, Long userId, Long pharmacyOrgId) {
        Organization clinic = organizations.lockById(callerOrg)
                .orElseThrow(() -> new LinkRefusedException("LINK_NOT_CLINIC", "Only a Hospital / Clinic can link a pharmacy."));
        if (!CLINIC.equalsIgnoreCase(clinic.getType()))
            throw new LinkRefusedException("LINK_NOT_CLINIC", "Only a Hospital / Clinic can link a pharmacy.");
        if (!links.findByClinicOrgIdAndStatusIn(callerOrg, List.of(OrgLink.REQUESTED, OrgLink.ACTIVE)).isEmpty())
            throw new LinkRefusedException("LINK_ALREADY", "This clinic already has a pharmacy, or is waiting for one. End that link first.");
        Organization pharmacy = pharmacyOrgId == null ? null : organizations.findById(pharmacyOrgId).orElse(null);
        boolean open = pharmacy != null && PHARMA.equalsIgnoreCase(pharmacy.getType())
                && "ACTIVE".equalsIgnoreCase(pharmacy.getStatus())
                && settings.findByOrganizationIdAndSettingKey(pharmacy.getId(), ACCEPT_KEY)
                        .map(s -> "true".equalsIgnoreCase(s.getSettingValue())).orElse(false);
        // A pharmacy that has not opted in answers exactly like one that does not exist: a clinic never learns it is there.
        if (!open) throw new LinkRefusedException("LINK_PHARMACY_CLOSED", "That pharmacy is not accepting clinics.");

        OrgLink l = new OrgLink();
        l.setClinicOrgId(callerOrg);
        l.setPharmacyOrgId(pharmacy.getId());
        l.setStatus(OrgLink.REQUESTED);
        l.setRequestedBy(userId);
        l.setRequestedAt(LocalDateTime.now());
        links.save(l);
        auditBoth(l, "LINK_REQUEST", null, OrgLink.REQUESTED, null, userId, clinic.getName(), pharmacy.getName(), callerOrg);
        return view(l, callerOrg);
    }

    // ── both sides ────────────────────────────────────────────────────────────────────────────────────────────

    /** My links, newest first — the other side's NAME only. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine(Long callerOrg) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OrgLink l : links.findByClinicOrgIdOrPharmacyOrgIdOrderByIdDesc(callerOrg, callerOrg)) out.add(view(l, callerOrg));
        return out;
    }

    @Transactional
    public Map<String, Object> accept(Long callerOrg, Long userId, Long linkId) {
        OrgLink l = links.findById(linkId == null ? -1L : linkId).orElse(null);
        // Only the ADDRESSED pharmacy: the clinic itself, another pharmacy and a stranger all get "not found".
        if (l == null || !callerOrg.equals(l.getPharmacyOrgId()))
            throw new LinkRefusedException("LINK_NOT_FOUND", "Link not found.");
        if (!OrgLink.REQUESTED.equals(l.getStatus()))
            throw new LinkRefusedException("LINK_NOT_WAITING", "This link is not waiting to be accepted.");
        l.setStatus(OrgLink.ACTIVE);
        l.setAcceptedBy(userId);
        l.setAcceptedAt(LocalDateTime.now());
        links.save(l);
        auditBoth(l, "LINK_ACCEPT", OrgLink.REQUESTED, OrgLink.ACTIVE, null, userId, null, null, callerOrg);
        return view(l, callerOrg);
    }

    @Transactional
    public Map<String, Object> end(Long callerOrg, Long userId, Long linkId, String reason) {
        OrgLink l = links.findById(linkId == null ? -1L : linkId).orElse(null);
        if (l == null || !l.involves(callerOrg)) throw new LinkRefusedException("LINK_NOT_FOUND", "Link not found.");
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) throw new LinkRefusedException("LINK_REASON_REQUIRED", "Say why the link is ending.");
        if (why.length() > 255) why = why.substring(0, 255);
        if (OrgLink.ENDED.equals(l.getStatus())) return view(l, callerOrg);   // ending twice changes nothing
        String before = l.getStatus();
        l.setStatus(OrgLink.ENDED);
        l.setEndedBy(userId);
        l.setEndedByOrgId(callerOrg);
        l.setEndedAt(LocalDateTime.now());
        l.setEndReason(why);
        links.save(l);
        auditBoth(l, "LINK_END", before, OrgLink.ENDED, why, userId, null, null, callerOrg);
        return view(l, callerOrg);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────

    private void requireType(Long callerOrg, String type) {
        Organization o = callerOrg == null ? null : organizations.findById(callerOrg).orElse(null);
        if (o == null || !type.equalsIgnoreCase(o.getType())) {
            throw CLINIC.equals(type)
                    ? new LinkRefusedException("LINK_NOT_CLINIC", "Only a Hospital / Clinic can link a pharmacy.")
                    : new LinkRefusedException("LINK_NOT_PHARMACY", "Only a pharmacy can accept clinics.");
        }
    }

    private String nameOf(Long orgId) {
        return organizations.findById(orgId).map(Organization::getName).orElse("");
    }

    /** The row as one side sees it: its role, the OTHER side's name, the status and the dates — nothing else. */
    private Map<String, Object> view(OrgLink l, Long callerOrg) {
        boolean asClinic = callerOrg.equals(l.getClinicOrgId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("role", asClinic ? "CLINIC" : "PHARMACY");
        m.put("otherName", nameOf(asClinic ? l.getPharmacyOrgId() : l.getClinicOrgId()));
        m.put("status", l.getStatus());
        m.put("requestedAt", l.getRequestedAt() == null ? null : l.getRequestedAt().toString());
        m.put("acceptedAt", l.getAcceptedAt() == null ? null : l.getAcceptedAt().toString());
        m.put("endedAt", l.getEndedAt() == null ? null : l.getEndedAt().toString());
        m.put("endReason", l.getEndReason());
        m.put("endedByMe", l.getEndedByOrgId() != null && l.getEndedByOrgId().equals(callerOrg));
        return m;
    }

    /** One trail row in EACH business; the other side's row names who acted. */
    private void auditBoth(OrgLink l, String action, String before, String after, String reason, Long userId,
                           String clinicName, String pharmacyName, Long actingOrg) {
        String clinic = clinicName != null ? clinicName : nameOf(l.getClinicOrgId());
        String pharmacy = pharmacyName != null ? pharmacyName : nameOf(l.getPharmacyOrgId());
        String actor = actingOrg.equals(l.getClinicOrgId()) ? clinic : pharmacy;
        audit.linkAction(action, l.getId(), l.getClinicOrgId(), before, after, reason, userId,
                "Pharmacy: " + pharmacy + " · by " + actor);
        audit.linkAction(action, l.getId(), l.getPharmacyOrgId(), before, after, reason, userId,
                "Clinic: " + clinic + " · by " + actor);
    }
}
