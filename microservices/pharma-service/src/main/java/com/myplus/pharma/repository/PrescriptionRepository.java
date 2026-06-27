package com.myplus.pharma.repository;

import com.myplus.pharma.entity.Prescription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {
    Page<Prescription> findByStatus(Prescription.Status status, Pageable pageable);
    Page<Prescription> findByPatientNameContainingIgnoreCase(String name, Pageable pageable);
    Page<Prescription> findByUserId(Long userId, Pageable pageable);

    // P5 (slice 41): tenant-scoped, NULL-fallback per the multi-tenancy standard.
    String SCOPE = "(p.organizationId = :orgId OR (p.organizationId IS NULL AND p.userId = :userId))";

    @Query("SELECT p FROM Prescription p WHERE " + SCOPE + " ORDER BY p.createdAt DESC")
    List<Prescription> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT p FROM Prescription p WHERE p.id = :id AND " + SCOPE)
    Optional<Prescription> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);
}
