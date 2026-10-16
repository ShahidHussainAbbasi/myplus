package com.myplus.pharma.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.ApiResponse;
import com.myplus.pharma.dto.AmendmentView;
import com.myplus.pharma.dto.PrescriptionDTO;
import com.myplus.pharma.service.LinkedRxService;

import lombok.RequiredArgsConstructor;

/**
 * HC-1d — what a LINKED clinic's doctor may ask of this pharmacy: its medicine list, send a prescription, read back
 * the prescriptions the clinic sent. Called by clinical-service with the doctor's forwarded identity.
 *
 * <p>The gateway routes {@code /api/pharma/**} with the prefix stripped, so this path IS reachable by a signed-in person.
 * That is safe by construction, not by obscurity: every call acts for the AUTHENTICATED organisation only, needs the
 * doctor's permission ({@code clinic.consult}) as the doctor's own Submit does, and the pharmacy is never a parameter —
 * {@link LinkedRxService} asks auth which pharmacy, on every call.
 */
@RestController
@RequestMapping("/internal/linked-rx")
@RequiredArgsConstructor
public class LinkedRxController {

    static final String DOCTOR = "hasAuthority('clinic.consult')";

    private final LinkedRxService linked;

    @PreAuthorize(DOCTOR)
    @GetMapping("/medicines")
    public ApiResponse<Map<String, Object>> medicines() {
        return ApiResponse.success(linked.medicines());
    }

    @PreAuthorize(DOCTOR)
    @PostMapping
    public ApiResponse<PrescriptionDTO> send(@RequestBody PrescriptionDTO dto) {
        return ApiResponse.success(linked.send(dto, CurrentUser.organizationId()), "Prescription sent");
    }

    @PreAuthorize(DOCTOR)
    @GetMapping("/{id}")
    public ApiResponse<PrescriptionDTO> get(@PathVariable Long id) {
        return ApiResponse.success(linked.get(id, CurrentUser.organizationId()));
    }

    @PreAuthorize(DOCTOR)
    @GetMapping("/{id}/amendments")
    public ApiResponse<List<AmendmentView>> amendments(@PathVariable Long id) {
        return ApiResponse.success(linked.amendments(id, CurrentUser.organizationId()));
    }

    /** A refusal is an answer: its sentence and a stable {@code data.code} the clinic's screen branches on (E5b shape). */
    @ExceptionHandler(LinkedRxService.LinkedRxRefusedException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> refused(LinkedRxService.LinkedRxRefusedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(false, e.getMessage(), Map.of("code", e.getCode()), HttpStatus.BAD_REQUEST.value()));
    }
}
