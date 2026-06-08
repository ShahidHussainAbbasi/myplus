package com.myplus.appointment.service;

import com.myplus.appointment.dto.AppointmentDTO;
import com.myplus.appointment.dto.BookingRequest;
import com.myplus.appointment.entity.Appointment;
import com.myplus.appointment.entity.Hospital;
import com.myplus.appointment.entity.Patient;
import com.myplus.appointment.exception.ResourceNotFoundException;
import com.myplus.appointment.repository.AppointmentRepository;
import com.myplus.appointment.repository.HospitalRepository;
import com.myplus.appointment.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentRepository repo;
    private final HospitalRepository hospitalRepo;
    private final PatientRepository patientRepo;
    private final ModelMapper mapper;

    @Transactional
    public AppointmentDTO create(AppointmentDTO dto, Long orgId) {
        Appointment a = mapper.map(dto, Appointment.class);
        a.setId(null);
        a.setOrganizationId(orgId);
        return mapper.map(repo.save(a), AppointmentDTO.class);
    }

    public List<AppointmentDTO> list(Long orgId) {
        return repo.findByOrganizationId(orgId).stream().map(a -> mapper.map(a, AppointmentDTO.class)).toList();
    }

    public List<AppointmentDTO> listByHospital(Long hospitalId, Long orgId) {
        return repo.findByHospitalIdAndOrganizationId(hospitalId, orgId).stream()
                .map(a -> mapper.map(a, AppointmentDTO.class)).toList();
    }

    public AppointmentDTO get(Long id, Long orgId) {
        return repo.findByIdAndOrganizationId(id, orgId).map(a -> mapper.map(a, AppointmentDTO.class))
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + id));
    }

    @Transactional
    public void delete(Long id, Long orgId) {
        Appointment a = repo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + id));
        repo.delete(a);
    }

    /** Anonymous public booking: org is inferred from the target hospital; a Patient is created. */
    @Transactional
    public AppointmentDTO bookPublic(BookingRequest req) {
        Hospital h = hospitalRepo.findById(req.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + req.getHospitalId()));
        Long orgId = h.getOrganizationId();
        Patient p = patientRepo.save(Patient.builder()
                .organizationId(orgId).name(req.getPatientName())
                .phone(req.getPatientPhone()).email(req.getPatientEmail()).build());
        Appointment a = repo.save(Appointment.builder()
                .organizationId(orgId).hospitalId(h.getId()).doctorId(req.getDoctorId()).patientId(p.getId())
                .appointmentType(req.getAppointmentType()).dateTime(req.getDateTime()).date(req.getDate())
                .patientsToVisit(1).patientsAppointed(1).patientsVisited(0).build());
        return mapper.map(a, AppointmentDTO.class);
    }
}
