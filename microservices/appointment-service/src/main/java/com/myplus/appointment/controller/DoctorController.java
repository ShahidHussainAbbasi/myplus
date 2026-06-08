package com.myplus.appointment.controller;

import com.myplus.appointment.dto.ApiResponse;
import com.myplus.appointment.dto.DoctorDTO;
import com.myplus.appointment.service.DoctorService;
import com.myplus.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/appointment/doctors")
@RequiredArgsConstructor
public class DoctorController {

    private final DoctorService service;

    @GetMapping
    public ApiResponse<List<DoctorDTO>> list(@RequestParam(required = false) Long hospitalId,
                                             @AuthenticationPrincipal AuthenticatedUser user) {
        Long org = user.getOrganizationId();
        return ApiResponse.success(hospitalId != null ? service.listByHospital(hospitalId, org) : service.list(org));
    }

    @PostMapping
    public ApiResponse<DoctorDTO> create(@Valid @RequestBody DoctorDTO dto,
                                         @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.create(dto, user.getOrganizationId()), "Doctor saved");
    }

    @GetMapping("/{id}")
    public ApiResponse<DoctorDTO> get(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.get(id, user.getOrganizationId()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, user.getOrganizationId());
        return ApiResponse.success(null, "Doctor deleted");
    }
}
