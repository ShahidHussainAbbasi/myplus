package com.myplus.auth.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.auth.entity.OrgLink;

/** HC-1c — clinic ↔ pharmacy links (V37). */
public interface OrgLinkRepository extends JpaRepository<OrgLink, Long> {

    List<OrgLink> findByClinicOrgIdAndStatusIn(Long clinicOrgId, Collection<String> statuses);

    List<OrgLink> findByClinicOrgIdOrPharmacyOrgIdOrderByIdDesc(Long clinicOrgId, Long pharmacyOrgId);
}
