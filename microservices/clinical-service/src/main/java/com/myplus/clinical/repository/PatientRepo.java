package com.myplus.clinical.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.clinical.entity.Patient;

import jakarta.persistence.LockModeType;

/**
 * Every read is scoped to ONE organisation, which callers take from the token (CurrentUser), never from the
 * request. A patient of another clinic is therefore indistinguishable from one that does not exist (anti-IDOR).
 * A clinic's patients are the clinic's, not a user's: there is no own-rows variant.
 */
public interface PatientRepo extends JpaRepository<Patient, Long> {

    Optional<Patient> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Everyone on one phone — the holder (family_seq 0) first. */
    List<Patient> findByOrganizationIdAndPhoneKeyOrderByFamilySeqAsc(Long organizationId, String phoneKey);

    @Query("select coalesce(max(p.familySeq), -1) from Patient p where p.organizationId = :org and p.phoneKey = :key")
    int maxFamilySeq(@Param("org") Long org, @Param("key") String phoneKey);

    /** The row lock that serialises linking one patient to its customer (a second link waits, then sees it). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Patient p where p.id = :id and p.organizationId = :org")
    Optional<Patient> lockScoped(@Param("id") Long id, @Param("org") Long org);

    /**
     * The front desk's search box: MRN or phone digits (prefix) or name (contains). Bounded by the caller's page —
     * a reception screen never loads a clinic's whole register.
     */
    @Query("select p from Patient p where p.organizationId = :org and p.status = 'ACTIVE' and ("
         + " p.mrn like concat(:q, '%') or p.phone like concat(:digits, '%') or lower(p.name) like concat('%', lower(:q), '%'))"
         + " order by p.id desc")
    List<Patient> search(@Param("org") Long org, @Param("q") String q, @Param("digits") String digits, Pageable page);

    @Query("select p from Patient p where p.organizationId = :org and p.status = 'ACTIVE' order by p.id desc")
    List<Patient> recent(@Param("org") Long org, Pageable page);
}
