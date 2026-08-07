package com.myplus.appointment.repository;

import com.myplus.appointment.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VenueRepository extends JpaRepository<Venue, Long> {
    List<Venue> findByOrganizationId(Long organizationId);
    Optional<Venue> findByIdAndOrganizationId(Long id, Long organizationId);
}
