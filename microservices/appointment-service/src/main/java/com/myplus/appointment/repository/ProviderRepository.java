package com.myplus.appointment.repository;

import com.myplus.appointment.entity.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProviderRepository extends JpaRepository<Provider, Long> {
    List<Provider> findByOrganizationId(Long organizationId);
    List<Provider> findByVenueIdAndOrganizationId(Long venueId, Long organizationId);
    List<Provider> findByVenueId(Long venueId);
    Optional<Provider> findByIdAndOrganizationId(Long id, Long organizationId);
}
