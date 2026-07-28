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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
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

    public List<PrescriptionDTO> list(Long orgId, Long userId) {
        return prescriptionRepo.findScoped(orgId, userId).stream().map(this::toDTO).collect(Collectors.toList());
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

    private PrescriptionDTO toDTO(Prescription p) {
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
        d.setItems(itemRepo.findByPrescriptionId(p.getId()).stream().map(i -> {
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
