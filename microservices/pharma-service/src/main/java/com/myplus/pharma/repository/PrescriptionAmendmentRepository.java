package com.myplus.pharma.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.pharma.entity.PrescriptionAmendment;

/** HMS H3b — a prescription's changes, oldest first; always read inside the organisation. */
public interface PrescriptionAmendmentRepository extends JpaRepository<PrescriptionAmendment, Long> {

    List<PrescriptionAmendment> findByOrganizationIdAndPrescriptionIdOrderByIdAsc(Long organizationId, Long prescriptionId);

    long countByOrganizationIdAndPrescriptionId(Long organizationId, Long prescriptionId);
}
