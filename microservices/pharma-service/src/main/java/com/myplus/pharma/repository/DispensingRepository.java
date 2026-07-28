package com.myplus.pharma.repository;

import com.myplus.pharma.entity.Dispensing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DispensingRepository extends JpaRepository<Dispensing, Long> {

    // NOTE: every finder here MUST carry the org scope. Unscoped derived queries (findByDispensedBy /
    // findByDispensedAtBetween / findByPatientName... / findByPrescriptionItemPrescriptionId) were removed —
    // unused, but a dispensing row names a patient, so a cross-tenant read here is a privacy breach.

    /**
     * B3 idempotency: has this prescription already been dispensed against this sale invoice? The sale flow
     * retries under an idempotency key and gets the SAME invoice back, which re-fires the dispense POST — without
     * this check the quantity is counted twice and the controlled register lists the dispense twice.
     * Reached only after the caller has scope-checked the parent prescription; org-scoped again here (defence in
     * depth, NULL-fallback by dispenser like the register below).
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(d) FROM Dispensing d WHERE d.prescriptionItem.prescription.id = :prescriptionId "
        + "AND d.invoiceNo = :invoiceNo "
        + "AND (d.organizationId = :orgId OR (d.organizationId IS NULL AND d.dispensedBy = :userId))")
    long countForInvoiceScoped(@org.springframework.data.repository.query.Param("prescriptionId") Long prescriptionId,
                               @org.springframework.data.repository.query.Param("invoiceNo") String invoiceNo,
                               @org.springframework.data.repository.query.Param("orgId") Long orgId,
                               @org.springframework.data.repository.query.Param("userId") Long userId);

    // P8 (slice 45): the controlled-substance register — controlled dispenses, org-scoped (NULL-fallback by user).
    @org.springframework.data.jpa.repository.Query(
        "SELECT d FROM Dispensing d WHERE d.controlled = true AND "
        + "(d.organizationId = :orgId OR (d.organizationId IS NULL AND d.dispensedBy = :userId)) "
        + "ORDER BY d.dispensedAt DESC")
    List<Dispensing> findControlledScoped(@org.springframework.data.repository.query.Param("orgId") Long orgId,
                                          @org.springframework.data.repository.query.Param("userId") Long userId,
                                          org.springframework.data.domain.Pageable pageable);
}
