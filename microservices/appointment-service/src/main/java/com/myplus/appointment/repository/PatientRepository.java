package com.myplus.appointment.repository;

import com.myplus.appointment.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PatientRepository extends JpaRepository<Patient, Long> {
    List<Patient> findByOrganizationId(Long organizationId);
}
