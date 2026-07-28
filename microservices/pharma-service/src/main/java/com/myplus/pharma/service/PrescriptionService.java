package com.myplus.pharma.service;

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

        LocalDate prescribed = dto.getPrescribedDate() != null ? dto.getPrescribedDate() : LocalDate.now();
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

    /** One query for the whole page's items, grouped by prescription id. */
    private Map<Long, List<PrescriptionItem>> itemsFor(List<Long> prescriptionIds) {
        Map<Long, List<PrescriptionItem>> byRx = new HashMap<>();
        for (Object[] row : itemRepo.findByPrescriptionIds(prescriptionIds))
            byRx.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((PrescriptionItem) row[1]);
        return byRx;
    }

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
        if (!terminal && p.getValidUntil() != null && p.getValidUntil().isBefore(LocalDate.now()))
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
