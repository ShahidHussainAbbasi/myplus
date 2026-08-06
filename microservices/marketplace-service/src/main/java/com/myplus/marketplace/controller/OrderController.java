package com.myplus.marketplace.controller;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.ApiResponse;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Orders back-office (E1, slice 46). Mapped at {@code /orders} → {@code /api/marketplace/orders} via the gateway
 * (StripPrefix=2). Tenant-scoped via CurrentUser.
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ApiResponse<OrderDTO> record(@RequestBody OrderDTO dto) {
        return ApiResponse.success(orderService.record(dto, CurrentUser.organizationId(), CurrentUser.userId()), "Order recorded");
    }

    @GetMapping
    public ApiResponse<List<OrderDTO>> list() {
        return ApiResponse.success(orderService.list(CurrentUser.organizationId(), CurrentUser.userId()));
    }

    /**
     * OMS O1 reconciliation — orders that never reached the books.
     *
     * <p>{@code GET /orders/reconciliation} lists the {@code LEGACY_UNPOSTED} backlog: storefront orders placed
     * before O1, which moved stock and possibly charged a card but produced no invoice, so they are missing from
     * the P&amp;L, tax register and AR. They are not back-posted (that would write into closed periods), so this
     * is how an operator finds them. Pass {@code ?booksStatus=POSTED} to audit the other side.
     *
     * <p>Owner/admin-gated: it is a financial-integrity view, not a shop-floor one.
     */
    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE','SUPER_PRIVILEGE')")
    @GetMapping("/reconciliation")
    public ApiResponse<List<OrderDTO>> reconciliation(
            @RequestParam(required = false) String booksStatus) {
        return ApiResponse.success(orderService.listByBooksStatus(
                booksStatus, CurrentUser.organizationId(), CurrentUser.userId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderDTO> get(@PathVariable Long id) {
        return ApiResponse.success(orderService.get(id, CurrentUser.organizationId(), CurrentUser.userId()));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<OrderDTO> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.success(
                orderService.updateStatus(id, body.get("status"), CurrentUser.organizationId(), CurrentUser.userId()), "Status updated");
    }

    /** Back-office refund (E6, slice 70). {@code amount} optional — omitted/0 = full remaining refund. */
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")   // a refund moves money — admin/owner only
    @PostMapping("/{id}/refund")
    public ApiResponse<OrderDTO> refund(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        java.math.BigDecimal amount = null;
        Object a = body == null ? null : body.get("amount");
        if (a != null && !a.toString().isBlank()) amount = new java.math.BigDecimal(a.toString());
        return ApiResponse.success(
                orderService.refund(id, amount, CurrentUser.organizationId(), CurrentUser.userId()), "Refund issued");
    }

    /** Back-office process a return (E10, slice 71) — stock back (G2) + refund (card) → RETURNED. */
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")   // a return issues a card refund — admin/owner only
    @PostMapping("/{id}/return")
    public ApiResponse<OrderDTO> processReturn(@PathVariable Long id) {
        return ApiResponse.success(
                orderService.processReturn(id, CurrentUser.organizationId(), CurrentUser.userId()), "Return processed");
    }
}
