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

    // P8 (slice 45): the controlled-substance register — controlled dispenses, org-scoped (NULL-fallback by user).
    @org.springframework.data.jpa.repository.Query(
        "SELECT d FROM Dispensing d WHERE d.controlled = true AND "
        + "(d.organizationId = :orgId OR (d.organizationId IS NULL AND d.dispensedBy = :userId)) "
        + "ORDER BY d.dispensedAt DESC")
    List<Dispensing> findControlledScoped(@org.springframework.data.repository.query.Param("orgId") Long orgId,
                                          @org.springframework.data.repository.query.Param("userId") Long userId);
}
