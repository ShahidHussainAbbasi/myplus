package com.myplus.pharma.service;

import com.myplus.pharma.dto.ClinicalDTO;
import com.myplus.pharma.dto.ControlledDispenseDTO;
import com.myplus.pharma.dto.InteractionDTO;
import com.myplus.pharma.dto.SafetyReportDTO;
import com.myplus.pharma.entity.DrugInteraction;
import com.myplus.pharma.entity.MedicineClinical;
import com.myplus.pharma.repository.DispensingRepository;
import com.myplus.pharma.repository.DrugInteractionRepository;
import com.myplus.pharma.repository.MedicineClinicalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Dispense safety (P7, slice 44) — clinical flags + interaction checks keyed by productId. {@link #check} powers the
 * pre-dispense warning and the dispense response; org/user passed in (controller reads CurrentUser) → unit-testable.
 */
@Service
@RequiredArgsConstructor
public class SafetyService {

    private final MedicineClinicalRepository clinicalRepo;
    private final DrugInteractionRepository interactionRepo;
    private final DispensingRepository dispensingRepo;

    /** Safety report for a set of items: which are rx-required / controlled, and any interactions among them. */
    public SafetyReportDTO check(List<Long> productIds, Long orgId, Long userId) {
        SafetyReportDTO report = new SafetyReportDTO();
        if (productIds == null || productIds.isEmpty()) return report;

        for (MedicineClinical c : clinicalRepo.findByProductIdsScoped(productIds, orgId, userId)) {
            if (c.isRxRequired()) report.getRxRequiredItems().add(c.getProductId());
            if (c.isControlledSubstance()) report.getControlledItems().add(c.getProductId());
        }
        for (DrugInteraction d : interactionRepo.findAmongScoped(productIds, orgId, userId)) {
            SafetyReportDTO.Interaction i = new SafetyReportDTO.Interaction();
            i.setProductId1(d.getProductId1()); i.setProductId2(d.getProductId2());
            i.setSeverity(d.getSeverity() != null ? d.getSeverity().name() : null);
            i.setDescription(d.getDescription()); i.setRecommendation(d.getRecommendation());
            report.getInteractions().add(i);
        }
        return report;
    }

    /** True if the item is a controlled substance (scoped) — used to flag the Dispensing record. */
    public boolean isControlled(Long productId, Long orgId, Long userId) {
        return clinicalRepo.findByProductIdScoped(productId, orgId, userId)
                .map(MedicineClinical::isControlledSubstance).orElse(false);
    }

    @Transactional
    public ClinicalDTO upsertClinical(ClinicalDTO dto, Long orgId, Long userId) {
        MedicineClinical c = clinicalRepo.findByProductIdScoped(dto.getProductId(), orgId, userId)
                .orElseGet(MedicineClinical::new);
        c.setOrganizationId(orgId);
        c.setUserId(userId);
        c.setProductId(dto.getProductId());
        c.setMedicineName(dto.getMedicineName());
        c.setRxRequired(dto.isRxRequired());
        c.setControlledSubstance(dto.isControlledSubstance());
        c.setDrugCategory(dto.getDrugCategory());
        clinicalRepo.save(c);
        return dto;
    }

    public List<ClinicalDTO> listClinical(Long orgId, Long userId) {
        return clinicalRepo.findScoped(orgId, userId).stream().map(c -> {
            ClinicalDTO d = new ClinicalDTO();
            d.setProductId(c.getProductId()); d.setMedicineName(c.getMedicineName());
            d.setRxRequired(c.isRxRequired()); d.setControlledSubstance(c.isControlledSubstance());
            d.setDrugCategory(c.getDrugCategory());
            return d;
        }).collect(Collectors.toList());
    }

    /** P8: the controlled-substance register — every controlled dispense, newest first, org-scoped. */
    public List<ControlledDispenseDTO> controlledRegister(Long orgId, Long userId) {
        return dispensingRepo.findControlledScoped(orgId, userId).stream().map(d -> {
            ControlledDispenseDTO r = new ControlledDispenseDTO();
            r.setDispensedAt(d.getDispensedAt());
            r.setProductId(d.getProductId());
            r.setMedicineName(d.getMedicineName());
            r.setQuantity(d.getQuantity());
            r.setPatientName(d.getPatientName());
            r.setInvoiceNo(d.getInvoiceNo());
            r.setDispensedBy(d.getDispensedBy());
            return r;
        }).collect(Collectors.toList());
    }

    @Transactional
    public void addInteraction(InteractionDTO dto, Long orgId, Long userId) {
        DrugInteraction.Severity sev;
        try { sev = DrugInteraction.Severity.valueOf(dto.getSeverity() == null ? "MODERATE" : dto.getSeverity().toUpperCase()); }
        catch (Exception e) { sev = DrugInteraction.Severity.MODERATE; }
        // Upsert the pair (tech-debt: avoid duplicate interaction rows for the same two items).
        DrugInteraction d = interactionRepo.findPairScoped(dto.getProductId1(), dto.getProductId2(), orgId, userId)
                .orElseGet(() -> DrugInteraction.builder()
                        .productId1(dto.getProductId1()).productId2(dto.getProductId2())
                        .organizationId(orgId).userId(userId).build());
        d.setSeverity(sev);
        d.setDescription(dto.getDescription());
        d.setRecommendation(dto.getRecommendation());
        interactionRepo.save(d);
    }
}
