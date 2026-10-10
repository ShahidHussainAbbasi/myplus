package com.myplus.business_service.controller;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.repository.CashMovementRepo;
import com.myplus.commerce.contracts.dto.DrawerPayoutView;
import com.myplus.common.security.CurrentUser;

import lombok.RequiredArgsConstructor;

/**
 * EX-9a — the till pay-outs of the CALLER's business that carry no expense number, for expense-service's owner-run
 * import of past pay-outs (R-4). Read-only. The organisation is the forwarded caller's: never a parameter. Reachable only
 * inside the private network (the gateway routes no {@code /internal/**}).
 */
@RestController
@RequiredArgsConstructor
public class InternalDrawerHistoryController {

    /** A screen of history at a time: the oldest first, and an import makes room for the next. */
    static final int MAX_ROWS = 500;

    private final CashMovementRepo movements;

    @GetMapping("/internal/business/drawer/unbooked-payouts")
    @Transactional(readOnly = true)
    public List<DrawerPayoutView> unbookedPayouts() {
        Long org = CurrentUser.organizationId();
        if (org == null) return List.of();
        return movements.payoutsWithoutExpense(org, PageRequest.of(0, MAX_ROWS)).stream()
                .map(m -> new DrawerPayoutView(m.getId(), m.getDated() == null ? null : m.getDated().toLocalDate(),
                        m.getAmount(), m.getReason(), m.getStoreId()))
                .toList();
    }

    /** Stamp an imported pay-out with its expense number, so it leaves the list above. Idempotent; scoped to the caller. */
    @PostMapping("/internal/business/drawer/payouts/{movementId}/expense-voucher")
    public void stampExpenseVoucher(@PathVariable("movementId") Long movementId, @RequestParam("voucherNo") String voucherNo) {
        Long org = CurrentUser.organizationId();
        if (org == null || voucherNo == null || voucherNo.isBlank() || voucherNo.length() > 20) return;
        movements.stampImported(org, movementId, voucherNo);
    }
}
