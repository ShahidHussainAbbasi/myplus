package com.myplus.pharma.repository;

import com.myplus.pharma.entity.Dispensing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DispensingRepository extends JpaRepository<Dispensing, Long> {
    Page<Dispensing> findByDispensedBy(Long dispensedBy, Pageable pageable);
    List<Dispensing> findByDispensedAtBetween(LocalDateTime start, LocalDateTime end);
    Page<Dispensing> findByPatientNameContainingIgnoreCase(String name, Pageable pageable);
    List<Dispensing> findByPrescriptionItemPrescriptionId(Long prescriptionId);

    // P8 (slice 45): the controlled-substance register — controlled dispenses, org-scoped (NULL-fallback by user).
    @org.springframework.data.jpa.repository.Query(
        "SELECT d FROM Dispensing d WHERE d.controlled = true AND "
        + "(d.organizationId = :orgId OR (d.organizationId IS NULL AND d.dispensedBy = :userId)) "
        + "ORDER BY d.dispensedAt DESC")
    List<Dispensing> findControlledScoped(@org.springframework.data.repository.query.Param("orgId") Long orgId,
                                          @org.springframework.data.repository.query.Param("userId") Long userId);
}
