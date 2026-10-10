package com.myplus.appointment.controller;

import com.myplus.appointment.dto.ApiResponse;
import com.myplus.appointment.dto.AppointmentDTO;
import com.myplus.appointment.dto.BookingRequest;
import com.myplus.appointment.service.AppointmentService;
import com.myplus.appointment.service.PublicDirectoryService;
import com.myplus.appointment.service.PublicDirectoryService.PublicDoctor;
import com.myplus.appointment.service.PublicDirectoryService.PublicVenue;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final PublicDirectoryService directory;

    // ── P-BOOK-1: what the anonymous booking page reads (public fields only) ──────────────────────────

    @GetMapping("/venues")
    public ApiResponse<List<PublicVenue>> venues() {
        return ApiResponse.success(directory.venues(), "Venues");
    }

    @GetMapping("/venues/{venueId}/doctors")
    public ApiResponse<List<PublicDoctor>> doctorsAt(@PathVariable Long venueId) {
        return ApiResponse.success(directory.doctorsAt(venueId), "Doctors");
    }

    @GetMapping("/doctors/{id}")
    public ApiResponse<PublicDoctor> doctor(@PathVariable Long id) {
        return ApiResponse.success(directory.doctor(id), "Doctor");
    }

    @PostMapping("/appointment-request")
    public ApiResponse<AppointmentDTO> book(@Valid @RequestBody BookingRequest request) {
        return ApiResponse.success(appointmentService.bookPublic(request), "Booking requested");
    }
}
