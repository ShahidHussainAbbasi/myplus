package com.myplus.pharma.repository;

import com.myplus.pharma.entity.Prescription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {
    Page<Prescription> findByStatus(Prescription.Status status, Pageable pageable);
    Page<Prescription> findByPatientNameContainingIgnoreCase(String name, Pageable pageable);
    Page<Prescription> findByUserId(Long userId, Pageable pageable);
}
