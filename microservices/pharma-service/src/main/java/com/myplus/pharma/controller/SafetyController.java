package com.myplus.pharma.controller;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.ApiResponse;
import com.myplus.pharma.dto.ClinicalDTO;
import com.myplus.pharma.dto.ControlledDispenseDTO;
import com.myplus.pharma.dto.InteractionDTO;
import com.myplus.pharma.dto.SafetyReportDTO;
import com.myplus.pharma.service.SafetyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
        // M5 (slice 100): productId-native; accept the legacy "itemIds" key too for back-compat during cutover.
        List<Long> productIds = body.getOrDefault("productIds", body.getOrDefault("itemIds", List.of()));
        return ApiResponse.success(safetyService.check(productIds, CurrentUser.organizationId(), CurrentUser.userId()));
    }

    @GetMapping("/clinical")
    public ApiResponse<List<ClinicalDTO>> listClinical() {
        return ApiResponse.success(safetyService.listClinical(CurrentUser.organizationId(), CurrentUser.userId()));
    }

    /**
     * Clinical flags are master data with a regulatory edge: clearing {@code controlledSubstance} silently drops
     * every later dispense off the controlled register. Admin/owner only — same tier as tax settings.
     */
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @PostMapping("/clinical")
    public ApiResponse<ClinicalDTO> upsertClinical(@RequestBody ClinicalDTO dto) {
        return ApiResponse.success(safetyService.upsertClinical(dto, CurrentUser.organizationId(), CurrentUser.userId()), "Saved");
    }

    /** An interaction warning is dispense-safety master data — admin/owner only, like the clinical flags. */
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @PostMapping("/interactions")
    public ApiResponse<Void> addInteraction(@RequestBody InteractionDTO dto) {
        safetyService.addInteraction(dto, CurrentUser.organizationId(), CurrentUser.userId());
        return ApiResponse.success(null, "Interaction added");
    }

    /**
     * P8 (slice 45): the controlled-substance register — a regulatory record carrying patient names against
     * controlled dispenses. Admin/owner only; a counter user has no business reading the whole register.
     */
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @GetMapping("/controlled-register")
    public ApiResponse<List<ControlledDispenseDTO>> controlledRegister() {
        return ApiResponse.success(safetyService.controlledRegister(CurrentUser.organizationId(), CurrentUser.userId()));
    }
}
