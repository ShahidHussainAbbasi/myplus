package com.myplus.appointment.repository;

import com.myplus.appointment.entity.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {
    List<Doctor> findByOrganizationId(Long organizationId);
    List<Doctor> findByHospitalIdAndOrganizationId(Long hospitalId, Long organizationId);
    List<Doctor> findByHospitalId(Long hospitalId);
    Optional<Doctor> findByIdAndOrganizationId(Long id, Long organizationId);
    long deleteByOrganizationId(Long organizationId);
}
