package com.myplus.marketplace.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.marketplace.dto.DeliveryRecordDTO;
import com.myplus.marketplace.dto.DriverSettlementDTO;
import com.myplus.marketplace.service.DriverSettlementService;

import lombok.RequiredArgsConstructor;

/**
 * OMS O7 D5 — driver settlement / remittance. Mapped at {@code /driver-settlements} →
 * {@code /api/marketplace/driver-settlements} via the gateway.
 *
 * <h3>Its own controller, not a branch of {@code OrderController}</h3>
 * A remittance spans orders — one bag of cash covers a round — so it is not addressable under
 * {@code /orders/{id}/…} without pretending it belongs to one of them.
 *
 * <h3>Everything here is admin-gated</h3>
 * Settling moves money into AR and closes the only control there is on a driver's cash, which is the same class
 * of action as {@code /delivery}, {@code /refund} and {@code /return}. <b>The reads are gated too</b>, and
 * deliberately: the open-collections list is a statement of who is holding how much of the company's money, and
 * the settlements list is the record of who was short. Neither is ordinary operational data.
 */
@RestController
@RequestMapping("/driver-settlements")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE','SUPER_PRIVILEGE')")
public class DriverSettlementController {

    private final DriverSettlementService service;

    /**
     * Cash keyed as collected and not yet handed over — the day-end worklist, oldest first.
     *
     * <p>{@code driver} narrows to one person's round. It is a FILTER within a tenant the caller can already
     * see, not a security boundary: org scoping in the query is what keeps tenants apart.
     */
    @GetMapping("/collections")
    public ApiResponse<PageResponse<DeliveryRecordDTO>> collections(
            @RequestParam(required = false) String driver,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(service.openCollections(driver, from, to, page, size,
                CurrentUser.organizationId(), CurrentUser.userId()));
    }

    /**
     * Count the bag and hand the money over — <b>and this is what posts the receipts to AR</b>.
     *
     * <p>The declared total and the variance are computed here from the collections themselves; a total sent by
     * the client would be OMS-5 in a new place.
     */
    @PostMapping
    public ApiResponse<DriverSettlementDTO> settle(@RequestBody DriverSettlementDTO body) {
        return ApiResponse.success(service.settle(body,
                CurrentUser.organizationId(), CurrentUser.userId(), CurrentUser.email()), "Driver settled");
    }

    /** Past remittances, newest first. */
    @GetMapping
    public ApiResponse<PageResponse<DriverSettlementDTO>> list(
            @RequestParam(required = false) String driver,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(service.list(driver, from, to, page, size,
                CurrentUser.organizationId(), CurrentUser.userId()));
    }

    /** One remittance, with the collections it swept up and the receipts it raised. */
    @GetMapping("/{id}")
    public ApiResponse<DriverSettlementDTO> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id, CurrentUser.organizationId(), CurrentUser.userId()));
    }
}
