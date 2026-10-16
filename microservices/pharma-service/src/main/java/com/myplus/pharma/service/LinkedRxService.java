package com.myplus.pharma.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.pharma.config.LinkedRxClients;
import com.myplus.pharma.dto.AmendmentView;
import com.myplus.pharma.dto.PrescriptionDTO;

import lombok.RequiredArgsConstructor;

/**
 * HC-1d — a Hospital / Clinic's doctor prescribing to the pharmacy it is LINKED to (a separate business). The pharmacy
 * side of R1–R3 and R5 (docs/slices/hc-1cd-clinic-pharmacy-link.md).
 *
 * <h3>Trust</h3>
 * The caller is ALWAYS the clinic: {@code clinicOrgId} is the authenticated org of the request (gateway-stamped, then
 * forwarded by clinical-service). Which pharmacy is NEVER taken from the caller — this service asks auth for the
 * clinic's route itself, on every call, so an ended link stops the next prescription at once and a clinic can only
 * ever reach the pharmacy it is linked to. Reads and checks against the pharmacy's catalogue run AS the pharmacy
 * ({@link GatewayIdentityForwarding#runAs}); nothing else about the pharmacy — price, stock, staff — is returned.
 */
@Service
@RequiredArgsConstructor
public class LinkedRxService {

    /** Refusal code: the clinic has no ACTIVE link to an ACTIVE pharmacy (or is not a clinic at all). */
    public static final String NO_LINK = "RX_NO_LINKED_PHARMACY";
    static final int PAGE = 2000, MAX_PAGES = 10;

    /**
     * The SYSTEM caller for a call made AS the pharmacy (catalogue read, product check, party bridge). Not a person: the
     * tenant travels, not the clinic's doctor, whose identity the pharmacy's services cannot verify. 0, as marketplace's
     * {@code AsOrg.SERVICE_USER} — a callee authenticates a forwarded identity only when it carries a user id
     * ({@code HeaderAuthFilter}), so null was refused 403 (HC-1d gate, 2026-10-16).
     */
    public static final Long SERVICE_USER = 0L;

    private final LinkedRxClients.Route routes;
    private final LinkedRxClients.Medicines catalog;
    private final PrescribedProductCheck productCheck;
    private final PrescriptionService prescriptions;
    private final PrescriptionAmendService amendments;

    /** A refusal the clinic's screen shows by its code. */
    public static class LinkedRxRefusedException extends RuntimeException {
        private final String code;
        public LinkedRxRefusedException(String code, String message) { super(message); this.code = code; }
        public String getCode() { return code; }
    }

    /** The linked pharmacy of the calling clinic: {pharmacyOrgId, pharmacyName, clinicName}, or refused. */
    public Map<String, Object> linkedPharmacy() {
        Map<String, Object> body = routes.route();
        Object data = body == null ? null : body.get("data");
        Map<?, ?> r = data instanceof Map<?, ?> m ? m : Map.of();
        if (!"LINKED".equals(r.get("route")) || r.get("pharmacyOrgId") == null) {
            throw new LinkedRxRefusedException(NO_LINK, "Your clinic is not linked to a pharmacy. Link one on Linked pharmacy, then send the prescription.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pharmacyOrgId", Long.valueOf(String.valueOf(r.get("pharmacyOrgId"))));
        out.put("pharmacyName", r.get("pharmacyName"));
        out.put("clinicName", r.get("clinicName"));
        return out;
    }

    /** R2 — the linked pharmacy's medicines: id, name and strength (formula) ONLY — never price, stock or anything else. */
    public Map<String, Object> medicines() {
        Map<String, Object> link = linkedPharmacy();
        Long pharmacy = (Long) link.get("pharmacyOrgId");
        List<Map<String, Object>> rows = new ArrayList<>();
        GatewayIdentityForwarding.runAs(SERVICE_USER, pharmacy, () -> {
            for (int page = 0; page < MAX_PAGES; page++) {
                Map<String, Object> resp = catalog.picker(page, PAGE);
                Map<?, ?> pg = resp != null && resp.get("data") instanceof Map<?, ?> m ? m : Map.of();
                Object content = pg.get("content");
                if (content instanceof List<?> list) {
                    for (Object o : list) {
                        if (!(o instanceof Map<?, ?> p) || p.get("id") == null) continue;
                        Map<String, Object> lean = new LinkedHashMap<>();
                        lean.put("id", Long.valueOf(String.valueOf(p.get("id"))));
                        lean.put("name", p.get("name"));
                        lean.put("formula", p.get("formula"));
                        rows.add(lean);
                    }
                }
                if (!(pg.get("last") instanceof Boolean last) || last) break;
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pharmacyName", link.get("pharmacyName"));
        out.put("medicines", rows);
        return out;
    }

    /** R3 — write the clinic's prescription in the LINKED pharmacy, after checking every line is in ITS list. */
    public PrescriptionDTO send(PrescriptionDTO dto, Long clinicOrgId) {
        if (dto == null) throw new ValidationException("A prescription is required");
        Map<String, Object> link = linkedPharmacy();
        Long pharmacy = (Long) link.get("pharmacyOrgId");
        RuntimeException[] refused = new RuntimeException[1];
        GatewayIdentityForwarding.runAs(SERVICE_USER, pharmacy, () -> {
            try { productCheck.assertInCatalogue(dto); } catch (RuntimeException e) { refused[0] = e; }
        });
        if (refused[0] != null) throw refused[0];
        PrescriptionDTO saved = prescriptions.createFromLinkedClinic(dto, pharmacy, clinicOrgId, (String) link.get("clinicName"));
        saved.setFromOrgName(String.valueOf(link.get("pharmacyName")));   // the CLINIC is told where it went
        return saved;
    }

    /** R5 — the clinic reads back its own prescription (status and lines). Allowed after the link ends: it is theirs. */
    public PrescriptionDTO get(Long id, Long clinicOrgId) {
        return prescriptions.getForLinkedClinic(id, clinicOrgId);
    }

    /** R5 — what the pharmacy changed, with WHO removed: a pharmacist's identity is the pharmacy's, not the clinic's. */
    public List<AmendmentView> amendments(Long id, Long clinicOrgId) {
        Long pharmacy = prescriptions.pharmacyOfLinked(id, clinicOrgId);
        return amendments.history(id, pharmacy, null).stream().map(a -> {
            a.setAmendedBy(null);
            return a;
        }).toList();
    }
}
