package com.myplus.appointment.controller;

import com.myplus.appointment.dto.ApiResponse;
import com.myplus.appointment.repository.AppointmentRepository;
import com.myplus.appointment.repository.DoctorRepository;
import com.myplus.appointment.repository.HospitalRepository;
import com.myplus.appointment.repository.PatientRepository;
import com.myplus.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * "Reset demo" support: deletes ALL of the caller's org data (hospitals/doctors/appointments/patients)
 * so a demo account can start from a clean slate. Org-scoped via the JWT (no cross-tenant impact).
 * Guarded to DEMO accounts only ({@code DEMO_PRIVILEGE}) so a real tenant can never wipe its data.
 */
@RestController
@RequestMapping("/api/appointment/demo")
@RequiredArgsConstructor
public class DemoPurgeController {

    private final HospitalRepository hospitalRepo;
    private final DoctorRepository doctorRepo;
    private final AppointmentRepository appointmentRepo;
    private final PatientRepository patientRepo;

    @DeleteMapping("/purge")
    @PreAuthorize("hasAuthority('DEMO_PRIVILEGE')")
    @Transactional
    public ApiResponse<Map<String, Object>> purge(@AuthenticationPrincipal AuthenticatedUser user) {
        Long org = user.getOrganizationId();
        long deleted = appointmentRepo.deleteByOrganizationId(org)
                + doctorRepo.deleteByOrganizationId(org)
                + patientRepo.deleteByOrganizationId(org)
                + hospitalRepo.deleteByOrganizationId(org);
        return ApiResponse.success(Map.of("deleted", deleted), "Demo data cleared");
    }
}
