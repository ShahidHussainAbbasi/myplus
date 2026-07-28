package com.myplus.pharma.service;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
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

import java.time.LocalDate;
import java.util.ArrayList;
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
        assertDispensable(rx);

        String invoiceNo = (req == null || req.getInvoiceNo() == null || req.getInvoiceNo().isBlank())
                ? null : req.getInvoiceNo().trim();

        // B3: the same sale posting twice (the sell flow retries under an idempotency key and gets the SAME
        // invoice back, which re-fires this call) must not count the quantity twice or double-list the dispense
        // on the controlled register. Return the current state, untouched, and say so.
        if (invoiceNo != null && dispensingRepo.countForInvoiceScoped(prescriptionId, invoiceNo, orgId, userId) > 0) {
            PrescriptionDTO already = prescriptionService.get(prescriptionId, orgId, userId);
            already.getWarnings().add("Already dispensed against invoice " + invoiceNo
                    + " — this repeat submission was ignored.");
            return already;
        }

        List<PrescriptionItem> items = itemRepo.findByPrescriptionId(prescriptionId);
        List<String> warnings = new ArrayList<>();
        int recorded = 0;

        if (req != null && req.getItems() != null) {
            for (DispenseRequest.Line line : req.getItems()) {
                if (line.getProductId() == null || line.getQuantity() <= 0) continue;
                PrescriptionItem pi = items.stream()
                        .filter(i -> line.getProductId().equals(i.getProductId()) /* M5: match by productId */)
                        .findFirst().orElse(null);
                // B4: these used to be silent `continue`s. The medicine has ALREADY left the counter on the sale,
                // so anything the prescription can't account for has to be said out loud.
                if (pi == null) {
                    warnings.add("Product #" + line.getProductId() + " was sold but is not on this prescription — "
                            + "not recorded as dispensed.");
                    continue;
                }
                int room = pi.getQuantity() - pi.getDispensedQuantity();
                int give = Math.min(room, line.getQuantity());
                if (give <= 0) {
                    warnings.add(label(pi) + " was already fully dispensed — the " + line.getQuantity()
                            + " sold now was not recorded against this prescription.");
                    continue;
                }
                if (give < line.getQuantity()) {
                    warnings.add(label(pi) + ": " + line.getQuantity() + " sold but only " + give
                            + " was still outstanding — recorded " + give + ".");
                }
                pi.setDispensedQuantity(pi.getDispensedQuantity() + give);
                itemRepo.save(pi);
                recorded++;
                boolean controlled = safetyService.isControlled(pi.getProductId(), orgId, userId);  // P7: flag for the controlled register
                dispensingRepo.save(Dispensing.builder()
                        .prescriptionItem(pi).productId(pi.getProductId()).medicineName(pi.getMedicineName())
                        .quantity(give).dispensedBy(userId).patientName(rx.getPatientName())
                        .invoiceNo(invoiceNo).organizationId(orgId).controlled(controlled)
                        .build());
            }
        }

        rx.setStatus(recomputeStatus(items));
        // Only stamp the dispenser when something was actually dispensed — a call that recorded nothing must not
        // rewrite who last dispensed this prescription.
        if (recorded > 0) {
            rx.setDispensedBy(userId);
            rx.setDispensedAt(java.time.LocalDateTime.now());
        }
        prescriptionRepo.save(rx);

        PrescriptionDTO dto = prescriptionService.get(prescriptionId, orgId, userId);
        dto.getWarnings().addAll(warnings);
        return dto;
    }

    /**
     * B2: a prescription is only dispensable while it is live. Expiry is DERIVED from validUntil rather than
     * stored — stamping EXPIRED here would be rolled back by the exception we throw on the same transaction, and
     * a date comparison needs no scheduler to stay true. A stored EXPIRED is still honoured (legacy rows).
     */
    private void assertDispensable(Prescription rx) {
        if (rx.getStatus() == Prescription.Status.CANCELLED)
            throw new ValidationException("This prescription was cancelled and cannot be dispensed.");
        if (rx.getStatus() == Prescription.Status.EXPIRED
                || (rx.getValidUntil() != null && rx.getValidUntil().isBefore(LocalDate.now())))
            throw new ValidationException("This prescription expired"
                    + (rx.getValidUntil() != null ? " on " + rx.getValidUntil() : "") + " and cannot be dispensed.");
    }

    private String label(PrescriptionItem pi) {
        return (pi.getMedicineName() == null || pi.getMedicineName().isBlank())
                ? "Product #" + pi.getProductId() : pi.getMedicineName();
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
