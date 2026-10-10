package com.myplus.pharma.controller;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.ApiResponse;
import com.myplus.pharma.dto.DispenseRequest;
import com.myplus.pharma.dto.PrescriptionDTO;
import com.myplus.pharma.service.DispenseService;
import com.myplus.pharma.service.PrescriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final com.myplus.pharma.service.PrescribedProductCheck prescribedProducts;

    /**
     * C3b — whether this tenant dispenses on prescription at all.
     *
     * <p><b>REQUIRED, deliberately.</b> {@code required = false} is how OMS O3 shipped a settings resolver
     * that silently did nothing, and a capability guard that disables itself when a bean is missing is worse
     * than no guard because it reads as protection. pharma-service now ships a {@code SettingsStore}
     * (C3b, {@code V7__org_setting.sql}), which is the condition the auto-configuration keys on; if that ever
     * stops being true this service must fail to start rather than quietly stop refusing.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private com.myplus.common.settings.CapabilityService capabilityService;

    /**
     * Intake is a write — a read-only/guest role must not be able to record a prescription.
     *
     * <p>Two gates, answering two different questions. {@code @PreAuthorize} asks whether this USER may write;
     * {@code assertEnabled} asks whether this TENANT does prescription trade at all. A hardware shop's owner
     * has every write privilege there is and still has no business recording a prescription.
     */
    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @PostMapping
    public ApiResponse<PrescriptionDTO> create(@RequestBody PrescriptionDTO dto) {
        capabilityService.assertEnabled(com.myplus.common.settings.Capability.RX_REQUIRED);
        PrescriptionService.assertMayClaimVisit(dto, CurrentUser.get().map(u -> u.getAuthorities() != null
                && u.getAuthorities().stream().anyMatch(a -> PrescriptionService.CLINIC_CONSULT.equals(a.getAuthority())))
                .orElse(false));
        // H1 (L-1): a doctor's Submit names only medicines this pharmacy has (live catalogue read, before anything is
        // written). Counter prescriptions keep today's behaviour: their screen picks from the same list.
        if (dto.getExternalRef() != null && !dto.getExternalRef().isBlank()) prescribedProducts.assertInCatalogue(dto);
        return ApiResponse.success(prescriptionService.create(dto, CurrentUser.organizationId(), CurrentUser.userId()), "Prescription recorded");
    }

    @GetMapping
    public ApiResponse<List<PrescriptionDTO>> list() {
        return ApiResponse.success(prescriptionService.list(CurrentUser.organizationId(), CurrentUser.userId()));
    }

    /** HMS S4-lite — paged search: {@code ?partyId=} one person, else {@code ?q=} text, else the newest. */
    @GetMapping("/search")
    public ApiResponse<java.util.Map<String, Object>> search(@org.springframework.web.bind.annotation.RequestParam(required = false) String q,
                                                             @org.springframework.web.bind.annotation.RequestParam(required = false) Long partyId,
                                                             @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
                                                             @org.springframework.web.bind.annotation.RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(prescriptionService.search(CurrentUser.organizationId(), CurrentUser.userId(), q, partyId, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<PrescriptionDTO> get(@PathVariable Long id) {
        return ApiResponse.success(prescriptionService.get(id, CurrentUser.organizationId(), CurrentUser.userId()));
    }

    /** Withdraw a prescription so it can no longer be dispensed. Anything already dispensed stays recorded. */
    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @PostMapping("/{id}/cancel")
    public ApiResponse<PrescriptionDTO> cancel(@PathVariable Long id) {
        return ApiResponse.success(
                prescriptionService.cancel(id, CurrentUser.organizationId(), CurrentUser.userId()), "Prescription cancelled");
    }

    /** P6 (slice 43): record a dispense against this prescription, fulfilled by a trade sale (invoiceNo). */
    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @PostMapping("/{id}/dispense")
    public ApiResponse<PrescriptionDTO> dispense(@PathVariable Long id, @RequestBody DispenseRequest req) {
        // Guarded as well as create(): a prescription recorded while the capability was on must not remain
        // dispensable after it is switched off. Guarding only intake would leave the stock-moving half open.
        capabilityService.assertEnabled(com.myplus.common.settings.Capability.RX_REQUIRED);
        return ApiResponse.success(
                dispenseService.dispense(id, req, CurrentUser.organizationId(), CurrentUser.userId()), "Dispensed");
    }
}
