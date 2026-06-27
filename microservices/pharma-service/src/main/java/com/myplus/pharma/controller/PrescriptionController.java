package com.myplus.pharma.controller;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.ApiResponse;
import com.myplus.pharma.dto.DispenseRequest;
import com.myplus.pharma.dto.PrescriptionDTO;
import com.myplus.pharma.service.DispenseService;
import com.myplus.pharma.service.PrescriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Prescriptions (P5, slice 41). Mapped at {@code /prescriptions} → {@code /api/pharma/prescriptions} via the gateway
 * (StripPrefix=2). Tenant-scoped via CurrentUser.
 */
@RestController
@RequestMapping("/prescriptions")
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionService prescriptionService;
    private final DispenseService dispenseService;

    @PostMapping
    public ApiResponse<PrescriptionDTO> create(@RequestBody PrescriptionDTO dto) {
        return ApiResponse.success(prescriptionService.create(dto, CurrentUser.organizationId(), CurrentUser.userId()), "Prescription recorded");
    }

    @GetMapping
    public ApiResponse<List<PrescriptionDTO>> list() {
        return ApiResponse.success(prescriptionService.list(CurrentUser.organizationId(), CurrentUser.userId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<PrescriptionDTO> get(@PathVariable Long id) {
        return ApiResponse.success(prescriptionService.get(id, CurrentUser.organizationId(), CurrentUser.userId()));
    }

    /** P6 (slice 43): record a dispense against this prescription, fulfilled by a trade sale (invoiceNo). */
    @PostMapping("/{id}/dispense")
    public ApiResponse<PrescriptionDTO> dispense(@PathVariable Long id, @RequestBody DispenseRequest req) {
        return ApiResponse.success(
                dispenseService.dispense(id, req, CurrentUser.organizationId(), CurrentUser.userId()), "Dispensed");
    }
}
