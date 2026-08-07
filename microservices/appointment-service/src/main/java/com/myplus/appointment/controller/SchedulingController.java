package com.myplus.appointment.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.myplus.appointment.dto.ApiResponse;
import com.myplus.appointment.service.SchedulingService;
import com.myplus.common.security.AuthenticatedUser;

/**
 * Slice SCHED-1 (B2) — the DOMAIN-NEUTRAL scheduling API.
 *
 * <h3>Why a second surface rather than changing the first</h3>
 *
 * {@code /api/appointment/**} keeps its clinic vocabulary and its exact JSON, because it is in front of
 * users and its field names ARE its contract (standard D9 form 6). This path is what new consumers use, and
 * education will use only this — it never sees a {@code doctorId}.
 *
 * <p>Expand now, contract later: two surfaces over one model is the cheap half of an API migration, and it
 * is what made this slice finishable without a simultaneous UI rewrite.
 *
 * <h3>No domain words appear here</h3>
 *
 * Providers, slots, attendees and an opaque {@code ref}. A school's parents' evening and a clinic's session
 * are both just a reference string this service never interprets — which is precisely the property
 * {@code appointment-service} lacked when it could not serve education at all.
 */
@RestController
@RequestMapping("/api/scheduling")
@RequiredArgsConstructor
public class SchedulingController {

    private final SchedulingService service;

    /**
     * Publish slots by cutting a window into equal pieces.
     *
     * <p>Idempotent: re-running the same generation creates nothing and reports what already existed, so
     * extending an evening adds only the new part.
     */
    @PostMapping("/slots/generate")
    public ApiResponse<Map<String, Object>> generate(@RequestParam Long providerId,
                                                     @RequestParam(required = false) Long venueId,
                                                     @RequestParam(required = false) String ref,
                                                     @RequestParam String from,
                                                     @RequestParam String to,
                                                     @RequestParam(defaultValue = "10") int minutes,
                                                     @RequestParam(defaultValue = "1") int capacity,
                                                     @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.generate(user.getOrganizationId(), providerId, venueId, ref,
                LocalDateTime.parse(from), LocalDateTime.parse(to), minutes, capacity), "Slots published");
    }

    /** What is published under one reference, each slot with how many places remain. */
    @GetMapping("/slots")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam String ref,
                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.listByRef(user.getOrganizationId(), ref));
    }

    /**
     * Book a slot. Idempotent per (slot, attendee) — a double-clicked Book returns the existing booking
     * with {@code alreadyBooked: true} rather than an error, because the caller asked for a booking and
     * has one.
     */
    @PostMapping("/bookings")
    public ApiResponse<Map<String, Object>> book(@RequestParam Long slotId,
                                                 @RequestParam Long attendeeId,
                                                 @RequestParam(required = false) String ref,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.book(user.getOrganizationId(), slotId, attendeeId, ref), "Booked");
    }

    @DeleteMapping("/bookings/{id}")
    public ApiResponse<Void> cancel(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.cancel(user.getOrganizationId(), id);
        return ApiResponse.success(null, "Booking cancelled");
    }
}
