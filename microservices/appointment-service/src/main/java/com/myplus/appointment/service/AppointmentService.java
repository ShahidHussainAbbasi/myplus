package com.myplus.appointment.service;

import com.myplus.appointment.dto.AppointmentDTO;
import com.myplus.appointment.dto.BookingRequest;
import com.myplus.appointment.entity.Appointment;
import com.myplus.appointment.entity.Doctor;
import com.myplus.appointment.entity.Hospital;
import com.myplus.appointment.entity.Patient;
import com.myplus.appointment.exception.ResourceNotFoundException;
import com.myplus.appointment.repository.AppointmentRepository;
import com.myplus.appointment.repository.DoctorRepository;
import com.myplus.appointment.repository.HospitalRepository;
import com.myplus.appointment.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentRepository repo;
    private final HospitalRepository hospitalRepo;
    private final PatientRepository patientRepo;
    private final DoctorRepository doctorRepo;
    private final ModelMapper mapper;

    @Transactional
    public AppointmentDTO create(AppointmentDTO dto, Long orgId) {
        Appointment a = mapper.map(dto, Appointment.class);
        a.setId(null);
        a.setOrganizationId(orgId);
        return mapper.map(repo.save(a), AppointmentDTO.class);
    }

    public List<AppointmentDTO> list(Long orgId) {
        return enrich(repo.findByOrganizationId(orgId), orgId);
    }

    public List<AppointmentDTO> listByHospital(Long hospitalId, Long orgId) {
        return enrich(repo.findByHospitalIdAndOrganizationId(hospitalId, orgId), orgId);
    }

    public AppointmentDTO get(Long id, Long orgId) {
        Appointment a = repo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + id));
        return enrich(List.of(a), orgId).get(0);
    }

    /** Map appointments to DTOs and resolve doctor/hospital/patient display names (org-scoped, one query each). */
    private List<AppointmentDTO> enrich(List<Appointment> appts, Long orgId) {
        Map<Long, Doctor> docs = doctorRepo.findByOrganizationId(orgId).stream()
                .collect(Collectors.toMap(Doctor::getId, d -> d, (a, b) -> a));
        Map<Long, Hospital> hosps = hospitalRepo.findByOrganizationId(orgId).stream()
                .collect(Collectors.toMap(Hospital::getId, h -> h, (a, b) -> a));
        Map<Long, Patient> pats = patientRepo.findByOrganizationId(orgId).stream()
                .collect(Collectors.toMap(Patient::getId, p -> p, (a, b) -> a));
        return appts.stream().map(a -> {
            AppointmentDTO dto = mapper.map(a, AppointmentDTO.class);
            Doctor d = a.getDoctorId() == null ? null : docs.get(a.getDoctorId());
            Hospital h = a.getHospitalId() == null ? null : hosps.get(a.getHospitalId());
            Patient p = a.getPatientId() == null ? null : pats.get(a.getPatientId());
            if (d != null) dto.setDoctorName(d.getName());
            if (h != null) dto.setHospitalName(h.getName());
            if (p != null) { dto.setPatientName(p.getName()); dto.setPatientPhone(p.getPhone()); }
            return dto;
        }).toList();
    }

    @Transactional
    public void delete(Long id, Long orgId) {
        Appointment a = repo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + id));
        repo.delete(a);
    }

    /**
     * Anonymous public booking. Org is inferred from the target hospital. Replicates the legacy flow:
     * compute the doctor's daily capacity, assign the next appointment number, enforce the daily limit,
     * reuse/create the patient (by phone within the org), and reject blocked patients.
     */
    @Transactional
    public AppointmentDTO bookPublic(BookingRequest req) {
        Hospital h = hospitalRepo.findById(req.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + req.getHospitalId()));
        Long orgId = h.getOrganizationId();
        Doctor d = doctorRepo.findById(req.getDoctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found: " + req.getDoctorId()));

        Patient patient = patientRepo.findFirstByPhoneAndOrganizationId(req.getPatientPhone(), orgId).orElse(null);
        if (patient != null && patient.isBlocked()) {
            throw new IllegalArgumentException("This number is blocked from booking appointments.");
        }

        int capacity = capacityFor(d);
        String date = req.getDate() != null ? req.getDate() : LocalDate.now().toString();
        int lastAppointed = repo.findFirstByHospitalIdAndDoctorIdAndDateOrderByIdDesc(h.getId(), d.getId(), date)
                .map(a -> a.getPatientsAppointed() == null ? 0 : a.getPatientsAppointed()).orElse(0);
        int appointed = lastAppointed + 1;
        if (appointed > capacity) {
            throw new IllegalArgumentException("Today's appointments reached the limit. Please try again tomorrow.");
        }

        if (patient == null) {
            patient = patientRepo.save(Patient.builder()
                    .organizationId(orgId).name(req.getPatientName()).phone(req.getPatientPhone())
                    .email(req.getPatientEmail()).address(req.getPatientAddress()).blocked(false).build());
        }

        Appointment a = repo.save(Appointment.builder()
                .organizationId(orgId).hospitalId(h.getId()).doctorId(d.getId()).patientId(patient.getId())
                .appointmentType(req.getAppointmentType())
                .dateTime(req.getDateTime() != null ? req.getDateTime() : LocalDateTime.now().toString())
                .date(date)
                .patientsToVisit(capacity == Integer.MAX_VALUE ? null : capacity)
                .patientsAppointed(appointed).patientsVisited(0).build());
        AppointmentDTO dto = mapper.map(a, AppointmentDTO.class);
        dto.setDoctorName(d.getName());
        dto.setHospitalName(h.getName());
        dto.setPatientName(patient.getName());
        dto.setPatientPhone(patient.getPhone());
        return dto;
    }

    /** Daily capacity: "count" -> fixed offerValue; time-based -> (hours*60)/offerValue; unknown -> unlimited. */
    private int capacityFor(Doctor d) {
        Integer val = d.getAppointmentOfferValue();
        if (val == null || val <= 0) {
            return Integer.MAX_VALUE;
        }
        if ("count".equalsIgnoreCase(d.getAppointmentOfferType())) {
            return val;
        }
        try {
            int hours = Integer.parseInt(d.getTimeOut().split(":")[0]) - Integer.parseInt(d.getTimeIn().split(":")[0]);
            int slots = (hours * 60) / val;
            return slots > 0 ? slots : Integer.MAX_VALUE;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }
}
