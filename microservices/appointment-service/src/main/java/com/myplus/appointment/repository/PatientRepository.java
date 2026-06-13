package com.myplus.appointment.repository;

import com.myplus.appointment.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {
    List<Patient> findByOrganizationId(Long organizationId);
    Optional<Patient> findFirstByPhoneAndOrganizationId(String phone, Long organizationId);
}
