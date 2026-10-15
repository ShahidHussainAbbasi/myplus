package com.myplus.clinical.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.clinical.entity.Encounter;

/** HMS S3a — consultations, always inside one organisation. */
public interface EncounterRepo extends JpaRepository<Encounter, Long> {

    Optional<Encounter> findByIdAndOrganizationId(Long id, Long organizationId);

    Optional<Encounter> findByOrganizationIdAndTokenId(Long organizationId, Long tokenId);

    /** The patient's earlier visits, newest first — the doctor's history panel. */
    @Query("select e from Encounter e where e.organizationId = :org and e.patientId = :patient and e.id <> :except order by e.id desc")
    List<Encounter> history(@Param("org") Long org, @Param("patient") Long patient, @Param("except") Long except, Pageable page);
}
