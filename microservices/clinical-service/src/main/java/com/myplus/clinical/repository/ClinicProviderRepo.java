package com.myplus.clinical.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.clinical.entity.ClinicProvider;

/** HMS S2 — each doctor's token letter, per clinic. */
public interface ClinicProviderRepo extends JpaRepository<ClinicProvider, ClinicProvider.Key> {

    Optional<ClinicProvider> findByOrganizationIdAndProviderId(Long organizationId, Long providerId);

    List<ClinicProvider> findByOrganizationId(Long organizationId);
}
