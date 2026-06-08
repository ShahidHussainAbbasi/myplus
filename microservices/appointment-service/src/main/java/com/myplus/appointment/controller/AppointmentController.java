package com.myplus.appointment.controller;

import com.myplus.appointment.dto.ApiResponse;
import com.myplus.appointment.dto.AppointmentDTO;
import com.myplus.appointment.service.AppointmentService;
import com.myplus.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/appointment/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService service;

    @GetMapping
    public ApiResponse<List<AppointmentDTO>> list(@RequestParam(required = false) Long hospitalId,
                                                  @AuthenticationPrincipal AuthenticatedUser user) {
        Long org = user.getOrganizationId();
        return ApiResponse.success(hospitalId != null ? service.listByHospital(hospitalId, org) : service.list(org));
    }

    @PostMapping
    public ApiResponse<AppointmentDTO> create(@Valid @RequestBody AppointmentDTO dto,
                                              @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.create(dto, user.getOrganizationId()), "Appointment saved");
    }

    @GetMapping("/{id}")
    public ApiResponse<AppointmentDTO> get(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.get(id, user.getOrganizationId()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, user.getOrganizationId());
        return ApiResponse.success(null, "Appointment deleted");
    }
}
