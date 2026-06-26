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

    @Transactional
    public PrescriptionDTO create(PrescriptionDTO dto, Long orgId, Long userId) {
        if (dto.getPatientName() == null || dto.getPatientName().isBlank())
            throw new ValidationException("Patient name is required");
        if (dto.getItems() == null || dto.getItems().isEmpty())
            throw new ValidationException("A prescription needs at least one item");

        Prescription p = Prescription.builder()
                .patientName(dto.getPatientName().trim())
                .patientPhone(dto.getPatientPhone())
                .doctorName(dto.getDoctorName())
                .doctorLicense(dto.getDoctorLicense())
                .prescribedDate(dto.getPrescribedDate() != null ? dto.getPrescribedDate() : LocalDate.now())
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
                    .itemId(it.getItemId())
                    .medicineName(it.getMedicineName())
                    .quantity(it.getQuantity())
                    .dosage(it.getDosage())
                    .frequency(it.getFrequency())
                    .duration(it.getDuration())
                    .dispensedQuantity(0)
                    .build());
        }
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
        d.setStatus(p.getStatus() != null ? p.getStatus().name() : null);
        d.setCreatedAt(p.getCreatedAt());
        d.setItems(itemRepo.findByPrescriptionId(p.getId()).stream().map(i -> {
            PrescriptionItemDTO id = new PrescriptionItemDTO();
            id.setId(i.getId());
            id.setItemId(i.getItemId());
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
