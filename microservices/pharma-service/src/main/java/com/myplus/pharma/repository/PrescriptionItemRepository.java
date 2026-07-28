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
}
