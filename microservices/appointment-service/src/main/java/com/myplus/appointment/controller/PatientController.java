package com.myplus.appointment.controller;

import com.myplus.appointment.dto.ApiResponse;
import com.myplus.appointment.dto.PatientDTO;
import com.myplus.appointment.service.PatientService;
import com.myplus.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/appointment/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService service;

    @GetMapping
    public ApiResponse<List<PatientDTO>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.list(user.getOrganizationId()));
    }

    @PostMapping
    public ApiResponse<PatientDTO> create(@Valid @RequestBody PatientDTO dto,
                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.create(dto, user.getOrganizationId()), "Patient saved");
    }
}
