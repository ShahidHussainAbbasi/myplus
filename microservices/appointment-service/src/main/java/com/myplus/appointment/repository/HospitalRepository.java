package com.myplus.appointment.repository;

import com.myplus.appointment.entity.Hospital;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HospitalRepository extends JpaRepository<Hospital, Long> {
    List<Hospital> findByOrganizationId(Long organizationId);
    Optional<Hospital> findByIdAndOrganizationId(Long id, Long organizationId);
    long deleteByOrganizationId(Long organizationId);
}
