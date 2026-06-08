package com.myplus.appointment.repository;

import com.myplus.appointment.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    List<Appointment> findByOrganizationId(Long organizationId);
    List<Appointment> findByHospitalIdAndOrganizationId(Long hospitalId, Long organizationId);
    Optional<Appointment> findByIdAndOrganizationId(Long id, Long organizationId);
    Optional<Appointment> findFirstByHospitalIdAndDoctorIdAndDateOrderByIdDesc(Long hospitalId, Long doctorId, String date);
}
