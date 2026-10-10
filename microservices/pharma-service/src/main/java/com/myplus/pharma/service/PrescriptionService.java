package com.myplus.pharma.service;

import com.myplus.common.security.time.TenantClock;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.pharma.dto.PrescriptionDTO;
import com.myplus.pharma.dto.PrescriptionItemDTO;
import com.myplus.pharma.entity.Prescription;
import com.myplus.pharma.entity.PrescriptionItem;
import com.myplus.pharma.repository.PrescriptionItemRepository;
import com.myplus.pharma.repository.PrescriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Prescription intake (P5, slice 41) — record a patient's prescription (prescriber + validity + prescribed items,
 * each a catalog product). The clinical record a dispense (P6) will reference; dispensing itself reuses the trade
 * saga sale. org/user are passed in (controller reads CurrentUser) so the logic is unit-testable. Org-scoped.
 */
@Service
@RequiredArgsConstructor
public class PrescriptionService {

    private final PrescriptionRepository prescriptionRepo;
    private final PrescriptionItemRepository itemRepo;
    private final PartyBridgeService partyBridgeService;   // P3: link the patient to the shared party master

    @Transactional
    public PrescriptionDTO create(PrescriptionDTO dto, Long orgId, Long userId) {
        if (dto.getPatientName() == null || dto.getPatientName().isBlank())
            throw new ValidationException("Patient name is required");
        if (dto.getItems() == null || dto.getItems().isEmpty())
            throw new ValidationException("A prescription needs at least one item");

        // Per-line validation. A zero/negative quantity is not merely odd: recomputeStatus would read the line as
        // already satisfied (dispensed 0 >= prescribed 0) and mark the whole prescription FULLY_DISPENSED on the
        // first dispense, so it must never reach the table.
        for (PrescriptionItemDTO it : dto.getItems()) {
            // Single quotes on purpose: this text travels as JSON through the gateway and the monolith proxy,
            // so it should not depend on every hop handling escaped double quotes correctly.
            String which = (it.getMedicineName() == null || it.getMedicineName().isBlank())
                    ? "A prescribed item" : "'" + it.getMedicineName().trim() + "'";
            if (it.getProductId() == null)
                throw new ValidationException(which + " has no medicine selected");
            if (it.getQuantity() <= 0)
                throw new ValidationException(which + " needs a quantity greater than zero");
        }

        // HMS S3b-1: a doctor's Submit carries "enc-<id>". The same key again (a retry, a double click, a lost reply)
        // is the SAME prescription — returned, never a second one for the pharmacy to dispense twice.
        String externalRef = dto.getExternalRef() == null || dto.getExternalRef().isBlank() ? null : dto.getExternalRef().trim();
        if (externalRef != null) {
            if (externalRef.length() > 64) throw new ValidationException("The prescription reference is too long");
            java.util.Optional<Prescription> already = prescriptionRepo.findByOrganizationIdAndExternalRef(orgId, externalRef);
            if (already.isPresent()) return toDTO(already.get());
        }
        boolean fromDoctor = externalRef != null;

        LocalDate prescribed = dto.getPrescribedDate() != null ? dto.getPrescribedDate() : TenantClock.today();
        if (dto.getValidUntil() != null && dto.getValidUntil().isBefore(prescribed))
            throw new ValidationException("'Valid until' cannot be before the prescribed date");

        Prescription p = Prescription.builder()
                .patientName(dto.getPatientName().trim())
                .patientPhone(dto.getPatientPhone())
                .doctorName(dto.getDoctorName())
                .doctorLicense(dto.getDoctorLicense())
                .prescribedDate(prescribed)
                .validUntil(dto.getValidUntil())
                .diagnosis(dto.getDiagnosis())
                .notes(dto.getNotes())
                .status(Prescription.Status.PENDING)
                // the source is DERIVED (a doctor's Submit is the one that carries an external reference), never read
                // from the form; the clinic's person id is taken only from that path, so the token search finds it now
                .source(fromDoctor ? Prescription.SOURCE_DOCTOR : Prescription.SOURCE_COUNTER)
                .externalRef(externalRef)
                .tokenLabel(fromDoctor ? trimTo(dto.getTokenLabel(), 16) : null)
                .encounterId(fromDoctor ? dto.getEncounterId() : null)
                .partyId(fromDoctor ? dto.getPartyId() : null)
                .organizationId(orgId)
                .userId(userId)
                .build();
        prescriptionRepo.save(p);

        for (PrescriptionItemDTO it : dto.getItems()) {
            itemRepo.save(PrescriptionItem.builder()
                    .prescription(p)
                    .productId(it.getProductId())
                    .medicineName(it.getMedicineName())
                    .quantity(it.getQuantity())
                    .dosage(it.getDosage())
                    .frequency(it.getFrequency())
                    .duration(it.getDuration())
                    .dispensedQuantity(0)
                    .build());
        }
        partyBridgeService.bridgePrescription(p);   // P3: link the patient to the shared party master (best-effort, once)
        return toDTO(p);
    }

    /** HMS S3b-1 — the permission that lets a caller submit a doctor's prescription (clinical-service's CONSULT). */
    public static final String CLINIC_CONSULT = "clinic.consult";

    /**
     * Only the doctor's Submit may carry a visit reference: without this, the counter form (which relays its body as
     * is) could post an {@code externalRef} and have a script shown as "From the doctor", tied to any person id.
     */
    public static void assertMayClaimVisit(PrescriptionDTO dto, boolean callerCanConsult) {
        boolean claims = dto != null && dto.getExternalRef() != null && !dto.getExternalRef().isBlank();
        if (claims && !callerCanConsult) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only a doctor's Submit from the clinic can record a prescription for a visit.");
        }
    }

    private static String trimTo(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Default page size for the prescriptions list — the screen shows recent scripts, not the whole history. */
    public static final int DEFAULT_LIMIT = 200;

    // Annotated on BOTH entry points: this delegates by self-invocation, which bypasses the proxy, so the
    // annotation on the 3-arg overload alone would never apply to a call that arrives here.
    @Transactional(readOnly = true)
    public List<PrescriptionDTO> list(Long orgId, Long userId) {
        return list(orgId, userId, DEFAULT_LIMIT);
    }

    /**
     * Newest-first, BOUNDED, and free of the N+1 this used to run: it returned every prescription the org had ever
     * recorded and then issued one item query per row. Now it is one page query plus one item query for the page.
     */
    @Transactional(readOnly = true)
    public List<PrescriptionDTO> list(Long orgId, Long userId, int limit) {
        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 1000);
        List<Prescription> page = prescriptionRepo.findScoped(orgId, userId, PageRequest.of(0, size));
        if (page.isEmpty()) return List.of();

        Map<Long, List<PrescriptionItem>> itemsByRx = itemsFor(page.stream().map(Prescription::getId).toList());
        return page.stream()
                .map(p -> toDTO(p, itemsByRx.getOrDefault(p.getId(), List.of())))
                .collect(Collectors.toList());
    }

    /**
     * HMS S4-lite — the pharmacist's search: by person ({@code partyId}, resolved upstream from a clinic token, MRN or
     * phone) or by text (name, phone, doctor); neither = the newest. One page; {@code hasMore} comes from reading one
     * row past the page, so there is no count query.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> search(Long orgId, Long userId, String q, Long partyId, int page, int size) {
        int s = size <= 0 ? 25 : Math.min(size, 100);
        int pg = Math.max(page, 0);
        PageRequest pr = PageRequest.of(pg, s + 1);
        String text = q == null ? "" : q.trim();
        List<Prescription> rows = partyId != null ? prescriptionRepo.findByPartyScoped(orgId, userId, partyId, pr)
                : text.isEmpty() ? prescriptionRepo.findScoped(orgId, userId, PageRequest.of(pg, s + 1))
                : prescriptionRepo.searchScoped(orgId, userId, text, pr);
        boolean more = rows.size() > s;
        List<Prescription> pageRows = more ? rows.subList(0, s) : rows;
        Map<Long, List<PrescriptionItem>> itemsByRx = pageRows.isEmpty() ? Map.of()
                : itemsFor(pageRows.stream().map(Prescription::getId).toList());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("items", pageRows.stream().map(p -> toDTO(p, itemsByRx.getOrDefault(p.getId(), List.of()))).toList());
        out.put("page", pg);
        out.put("size", s);
        out.put("hasMore", more);
        return out;
    }

    /** One query for the whole page's items, grouped by prescription id. */
    private Map<Long, List<PrescriptionItem>> itemsFor(List<Long> prescriptionIds) {
        Map<Long, List<PrescriptionItem>> byRx = new HashMap<>();
        for (Object[] row : itemRepo.findByPrescriptionIds(prescriptionIds))
            byRx.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((PrescriptionItem) row[1]);
        return byRx;
    }

    @Transactional(readOnly = true)
    public PrescriptionDTO get(Long id, Long orgId, Long userId) {
        Prescription p = prescriptionRepo.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));
        return toDTO(p);
    }

    /**
     * Cancel a prescription so it can no longer be dispensed (script withdrawn, entered in error, patient
     * deceased). Without this the CANCELLED state was unreachable and the dispense guard that checks for it was
     * dead code. Anything already dispensed stays on the record — cancelling stops FUTURE dispensing only.
     */
    @Transactional
    public PrescriptionDTO cancel(Long id, Long orgId, Long userId) {
        Prescription p = prescriptionRepo.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));
        if (p.getStatus() == Prescription.Status.FULLY_DISPENSED)
            throw new ValidationException("A fully dispensed prescription cannot be cancelled");
        if (p.getStatus() == Prescription.Status.CANCELLED) return toDTO(p);   // already there — idempotent
        p.setStatus(Prescription.Status.CANCELLED);
        prescriptionRepo.save(p);
        return toDTO(p);
    }

    /**
     * Expiry is DERIVED, not stored (see DispenseService.assertDispensable): a script past its validUntil reads
     * as EXPIRED everywhere without a nightly job that could silently stop running. A terminal stored state
     * (FULLY_DISPENSED / CANCELLED) always wins — a filled script doesn't become "expired" the next day.
     */
    private String displayStatus(Prescription p) {
        Prescription.Status s = p.getStatus();
        if (s == null) return null;
        boolean terminal = s == Prescription.Status.FULLY_DISPENSED || s == Prescription.Status.CANCELLED;
        if (!terminal && p.getValidUntil() != null && p.getValidUntil().isBefore(TenantClock.today()))
            return Prescription.Status.EXPIRED.name();
        return s.name();
    }

    /** Single-prescription mapping — fetches its own items (one row, so no N+1 to avoid). */
    private PrescriptionDTO toDTO(Prescription p) {
        return toDTO(p, itemRepo.findByPrescriptionId(p.getId()));
    }

    /** Mapping with the items supplied — used by the list path, which loads a whole page's items in one query. */
    private PrescriptionDTO toDTO(Prescription p, List<PrescriptionItem> items) {
        PrescriptionDTO d = new PrescriptionDTO();
        d.setId(p.getId());
        d.setPatientName(p.getPatientName());
        d.setPatientPhone(p.getPatientPhone());
        d.setDoctorName(p.getDoctorName());
        d.setDoctorLicense(p.getDoctorLicense());
        d.setPrescribedDate(p.getPrescribedDate());
        d.setValidUntil(p.getValidUntil());
        d.setDiagnosis(p.getDiagnosis());
        d.setNotes(p.getNotes());
        d.setStatus(displayStatus(p));
        d.setPartyId(p.getPartyId());   // P3: shared party master id
        d.setSource(p.getSource());
        d.setTokenLabel(p.getTokenLabel());
        d.setEncounterId(p.getEncounterId());
        d.setExternalRef(p.getExternalRef());
        d.setCreatedAt(p.getCreatedAt());
        d.setItems(items.stream().map(i -> {
            PrescriptionItemDTO id = new PrescriptionItemDTO();
            id.setId(i.getId());
            id.setProductId(i.getProductId());
            id.setMedicineName(i.getMedicineName());
            id.setQuantity(i.getQuantity());
            id.setDosage(i.getDosage());
            id.setFrequency(i.getFrequency());
            id.setDuration(i.getDuration());
            id.setDispensedQuantity(i.getDispensedQuantity());
            return id;
        }).collect(Collectors.toList()));
        return d;
    }
}
