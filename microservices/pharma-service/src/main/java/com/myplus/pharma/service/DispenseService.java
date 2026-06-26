package com.myplus.pharma.service;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.pharma.dto.DispenseRequest;
import com.myplus.pharma.dto.PrescriptionDTO;
import com.myplus.pharma.entity.Dispensing;
import com.myplus.pharma.entity.Prescription;
import com.myplus.pharma.entity.PrescriptionItem;
import com.myplus.pharma.repository.DispensingRepository;
import com.myplus.pharma.repository.PrescriptionItemRepository;
import com.myplus.pharma.repository.PrescriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Dispense (P6, slice 43) — the clinical record of fulfilling a prescription via a trade sale. The sale itself
 * (stock/tax/payment/receipt) is the reused POS saga; this only bumps each prescribed line's dispensedQuantity
 * (capped), writes a Dispensing row linked to the sale invoice, and recomputes the prescription status. Org-scoped.
 */
@Service
@RequiredArgsConstructor
public class DispenseService {

    private final PrescriptionRepository prescriptionRepo;
    private final PrescriptionItemRepository itemRepo;
    private final DispensingRepository dispensingRepo;
    private final PrescriptionService prescriptionService;
    private final SafetyService safetyService;

    @Transactional
    public PrescriptionDTO dispense(Long prescriptionId, DispenseRequest req, Long orgId, Long userId) {
        Prescription rx = prescriptionRepo.findByIdScoped(prescriptionId, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));
        List<PrescriptionItem> items = itemRepo.findByPrescriptionId(prescriptionId);

        if (req != null && req.getItems() != null) {
            for (DispenseRequest.Line line : req.getItems()) {
                if (line.getItemId() == null || line.getQuantity() <= 0) continue;
                PrescriptionItem pi = items.stream()
                        .filter(i -> line.getItemId().equals(i.getItemId()))
                        .findFirst().orElse(null);
                if (pi == null) continue;                          // not a prescribed item — skip
                int room = pi.getQuantity() - pi.getDispensedQuantity();
                int give = Math.min(room, line.getQuantity());
                if (give <= 0) continue;
                pi.setDispensedQuantity(pi.getDispensedQuantity() + give);
                itemRepo.save(pi);
                boolean controlled = safetyService.isControlled(pi.getItemId(), orgId, userId);  // P7: flag for the controlled register
                dispensingRepo.save(Dispensing.builder()
                        .prescriptionItem(pi).itemId(pi.getItemId()).medicineName(pi.getMedicineName())
                        .quantity(give).dispensedBy(userId).patientName(rx.getPatientName())
                        .invoiceNo(req.getInvoiceNo()).organizationId(orgId).controlled(controlled)
                        .build());
            }
        }

        rx.setStatus(recomputeStatus(items));
        rx.setDispensedBy(userId);
        rx.setDispensedAt(java.time.LocalDateTime.now());
        prescriptionRepo.save(rx);
        return prescriptionService.get(prescriptionId, orgId, userId);
    }

    /** FULLY when every line is fully dispensed; PARTIALLY when some qty dispensed; else PENDING. */
    private Prescription.Status recomputeStatus(List<PrescriptionItem> items) {
        boolean allFull = !items.isEmpty(), anyDispensed = false;
        for (PrescriptionItem i : items) {
            if (i.getDispensedQuantity() < i.getQuantity()) allFull = false;
            if (i.getDispensedQuantity() > 0) anyDispensed = true;
        }
        return allFull ? Prescription.Status.FULLY_DISPENSED
                : anyDispensed ? Prescription.Status.PARTIALLY_DISPENSED : Prescription.Status.PENDING;
    }
}
