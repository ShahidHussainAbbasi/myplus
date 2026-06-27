package com.myplus.pharma.controller;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.ApiResponse;
import com.myplus.pharma.dto.ClinicalDTO;
import com.myplus.pharma.dto.ControlledDispenseDTO;
import com.myplus.pharma.dto.InteractionDTO;
import com.myplus.pharma.dto.SafetyReportDTO;
import com.myplus.pharma.service.SafetyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Pharmacy safety (P7, slice 44) — clinical flags + drug-interaction checks. Mapped at root paths
 * ({@code /safety/check}, {@code /clinical}, {@code /interactions}) → {@code /api/pharma/...} via the gateway.
 */
@RestController
@RequiredArgsConstructor
public class SafetyController {

    private final SafetyService safetyService;

    @PostMapping("/safety/check")
    public ApiResponse<SafetyReportDTO> check(@RequestBody Map<String, List<Long>> body) {
        List<Long> itemIds = body.getOrDefault("itemIds", List.of());
        return ApiResponse.success(safetyService.check(itemIds, CurrentUser.organizationId(), CurrentUser.userId()));
    }

    @GetMapping("/clinical")
    public ApiResponse<List<ClinicalDTO>> listClinical() {
        return ApiResponse.success(safetyService.listClinical(CurrentUser.organizationId(), CurrentUser.userId()));
    }

    @PostMapping("/clinical")
    public ApiResponse<ClinicalDTO> upsertClinical(@RequestBody ClinicalDTO dto) {
        return ApiResponse.success(safetyService.upsertClinical(dto, CurrentUser.organizationId(), CurrentUser.userId()), "Saved");
    }

    @PostMapping("/interactions")
    public ApiResponse<Void> addInteraction(@RequestBody InteractionDTO dto) {
        safetyService.addInteraction(dto, CurrentUser.organizationId(), CurrentUser.userId());
        return ApiResponse.success(null, "Interaction added");
    }

    /** P8 (slice 45): the controlled-substance register. */
    @GetMapping("/controlled-register")
    public ApiResponse<List<ControlledDispenseDTO>> controlledRegister() {
        return ApiResponse.success(safetyService.controlledRegister(CurrentUser.organizationId(), CurrentUser.userId()));
    }
}
