package com.myplus.appointment.controller;

import com.myplus.appointment.dto.ApiResponse;
import com.myplus.appointment.dto.AppointmentDTO;
import com.myplus.appointment.dto.BookingRequest;
import com.myplus.appointment.service.AppointmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous public patient booking. Open (no auth): permitted by SecurityConfig ("/api/appointment/public/**")
 * and the gateway's OPEN_API_ENDPOINTS. Org is inferred from the target hospital.
 */
@RestController
@RequestMapping("/api/appointment/public")
@RequiredArgsConstructor
public class PublicBookingController {

    private final AppointmentService appointmentService;

    @PostMapping("/appointment-request")
    public ApiResponse<AppointmentDTO> book(@Valid @RequestBody BookingRequest request) {
        return ApiResponse.success(appointmentService.bookPublic(request), "Appointment requested");
    }
}
