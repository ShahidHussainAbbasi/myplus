package com.myplus.appointment.controller;

import com.myplus.appointment.dto.ApiResponse;
import com.myplus.appointment.dto.HospitalDTO;
import com.myplus.appointment.service.HospitalService;
import com.myplus.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/appointment/hospitals")
@RequiredArgsConstructor
public class HospitalController {

    private final HospitalService service;

    @GetMapping
    public ApiResponse<List<HospitalDTO>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.list(user.getOrganizationId()));
    }

    @PostMapping
    public ApiResponse<HospitalDTO> create(@Valid @RequestBody HospitalDTO dto,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.create(dto, user.getOrganizationId()), "Hospital saved");
    }

    @GetMapping("/{id}")
    public ApiResponse<HospitalDTO> get(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.get(id, user.getOrganizationId()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, user.getOrganizationId());
        return ApiResponse.success(null, "Hospital deleted");
    }
}
