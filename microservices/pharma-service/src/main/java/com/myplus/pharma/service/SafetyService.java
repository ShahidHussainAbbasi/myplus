package com.myplus.pharma.service;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;
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
 *
 * B1 (rx enforcement): the two flags the SELL path needs — {@code rxRequired} / {@code controlledSubstance} — now
 * live on the catalog product, because the sell saga already holds a ProductRef per line and must not call this
 * service at checkout. Catalog is the single writer; this service reads them back through {@link CatalogClient} and
 * still owns the richer clinical layer (drug category) and drug interactions entirely.
 */
@Service
@RequiredArgsConstructor
public class SafetyService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SafetyService.class);

    private final MedicineClinicalRepository clinicalRepo;
    private final DrugInteractionRepository interactionRepo;
    private final DispensingRepository dispensingRepo;
    private final CatalogClient catalogClient;   // B1: catalog owns rxRequired / controlledSubstance

    /** Safety report for a set of items: which are rx-required / controlled, and any interactions among them. */
    public SafetyReportDTO check(List<Long> productIds, Long orgId, Long userId) {
        SafetyReportDTO report = new SafetyReportDTO();
        if (productIds == null || productIds.isEmpty()) return report;

        // Flags come from catalog (single source of truth) in ONE batch call for the whole basket — this is the
        // pre-dispense warning path, not the sell hot path.
        for (ProductRef p : refs(productIds)) {
            if (Boolean.TRUE.equals(p.getRxRequired())) report.getRxRequiredItems().add(p.getId());
            if (Boolean.TRUE.equals(p.getControlledSubstance())) report.getControlledItems().add(p.getId());
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

    /** True if the item is a controlled substance — read from catalog (single source of truth). */
    public boolean isControlled(Long productId, Long orgId, Long userId) {
        if (productId == null) return false;
        return controlledSet(List.of(productId), orgId, userId).contains(productId);
    }

    /**
     * The controlled subset of a basket, in ONE call. The dispense loop used to ask per line, which was a query
     * per line before and a catalog round trip per line after the flags moved — a dispense of ten lines paid ten
     * times over for something one batch answers.
     */
    public java.util.Set<Long> controlledSet(List<Long> productIds, Long orgId, Long userId) {
        if (productIds == null || productIds.isEmpty()) return java.util.Set.of();
        return refs(productIds.stream().filter(java.util.Objects::nonNull).distinct().toList()).stream()
                .filter(r -> Boolean.TRUE.equals(r.getControlledSubstance()))
                .map(ProductRef::getId)
                .collect(Collectors.toSet());
    }

    /**
     * Save a medicine's clinical record. The two enforcement flags go to CATALOG (single writer — the sell guard
     * reads them from the product master); the clinical extras stay here. The catalog write happens FIRST: if it
     * fails the caller gets the error rather than a local row that claims a flag the tills will never honour.
     */
    @Transactional
    public ClinicalDTO upsertClinical(ClinicalDTO dto, Long orgId, Long userId) {
        catalogClient.updateClinicalFlags(dto.getProductId(), dto.isRxRequired(), dto.isControlledSubstance());

        MedicineClinical c = clinicalRepo.findByProductIdScoped(dto.getProductId(), orgId, userId)
                .orElseGet(MedicineClinical::new);
        c.setOrganizationId(orgId);
        c.setUserId(userId);
        c.setProductId(dto.getProductId());
        c.setMedicineName(dto.getMedicineName());
        // Mirrored for the backfill/rollback window only — NOT read for enforcement any more (see class javadoc).
        c.setRxRequired(dto.isRxRequired());
        c.setControlledSubstance(dto.isControlledSubstance());
        c.setDrugCategory(dto.getDrugCategory());
        clinicalRepo.save(c);
        return dto;
    }

    /** The flagged-medicines table. Flags are read back from catalog so the screen shows what the tills enforce. */
    public List<ClinicalDTO> listClinical(Long orgId, Long userId) {
        return listClinical(orgId, userId, REGISTER_LIMIT);
    }

    public List<ClinicalDTO> listClinical(Long orgId, Long userId, int limit) {
        int size = limit <= 0 ? REGISTER_LIMIT : Math.min(limit, 5000);
        List<MedicineClinical> rows = clinicalRepo.findScoped(orgId, userId,
                org.springframework.data.domain.PageRequest.of(0, size));
        java.util.Map<Long, ProductRef> byId = refs(rows.stream()
                .map(MedicineClinical::getProductId).filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(ProductRef::getId, r -> r, (a, b) -> a));
        return rows.stream().map(c -> {
            ProductRef ref = byId.get(c.getProductId());
            ClinicalDTO d = new ClinicalDTO();
            d.setProductId(c.getProductId());
            d.setMedicineName(ref != null && ref.getName() != null ? ref.getName() : c.getMedicineName());
            d.setRxRequired(ref != null ? Boolean.TRUE.equals(ref.getRxRequired()) : c.isRxRequired());
            d.setControlledSubstance(ref != null ? Boolean.TRUE.equals(ref.getControlledSubstance())
                    : c.isControlledSubstance());
            d.setDrugCategory(c.getDrugCategory());
            return d;
        }).collect(Collectors.toList());
    }

    /** Batch product refs, tolerating a catalog hiccup — a failed lookup must not blow up a warning screen. */
    private List<ProductRef> refs(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) return List.of();
        try {
            List<ProductRef> out = catalogClient.getProducts(productIds);
            return out != null ? out : List.of();
        } catch (Exception e) {
            LOG.warn("catalog lookup failed for {} product(s) — clinical flags unavailable", productIds.size(), e);
            return List.of();
        }
    }

    /** Default page for the controlled register — a regulatory log grows without bound, the screen shows recent. */
    public static final int REGISTER_LIMIT = 500;

    /** P8: the controlled-substance register — controlled dispenses, newest first, org-scoped, bounded. */
    public List<ControlledDispenseDTO> controlledRegister(Long orgId, Long userId) {
        return controlledRegister(orgId, userId, REGISTER_LIMIT);
    }

    public List<ControlledDispenseDTO> controlledRegister(Long orgId, Long userId, int limit) {
        int size = limit <= 0 ? REGISTER_LIMIT : Math.min(limit, 5000);
        return dispensingRepo.findControlledScoped(orgId, userId,
                org.springframework.data.domain.PageRequest.of(0, size)).stream().map(d -> {
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
