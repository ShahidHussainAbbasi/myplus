package com.myplus.pharma.repository;

import com.myplus.pharma.entity.PrescriptionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrescriptionItemRepository extends JpaRepository<PrescriptionItem, Long> {
    // Reached only via a prescription the caller already passed findByIdScoped/findScoped on, so the parent
    // carries the tenant check. Unscoped findByProductId was removed (unused — it would have spanned tenants).
    List<PrescriptionItem> findByPrescriptionId(Long prescriptionId);

    /**
     * All items for a page of prescriptions in ONE query — kills the N+1 the list screen used to run (one item
     * query per prescription). Returns {@code [prescriptionId, item]} rows rather than navigating
     * {@code item.getPrescription()}, so nothing touches a lazy proxy outside the session.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT i.prescription.id, i FROM PrescriptionItem i WHERE i.prescription.id IN :prescriptionIds")
    List<Object[]> findByPrescriptionIds(
        @org.springframework.data.repository.query.Param("prescriptionIds") List<Long> prescriptionIds);
}
