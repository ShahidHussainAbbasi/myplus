package com.myplus.agriculture.controller;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.agriculture.repository.AgricultureExpenseRepo;
import com.myplus.common.security.CurrentUser;

/**
 * EX-9b — the CALLER's farm expense rows that are not in the books yet, for expense-service's owner-run import (R-4), and
 * the stamp that marks one imported. The organisation is the forwarded caller's, never a parameter. Reachable only inside
 * the private network (the gateway routes no {@code /internal/**}).
 */
@RestController
public class InternalFarmHistoryController {

    static final int MAX_ROWS = 500;

    /** The wire shape expense-service reads (commerce-contracts {@code FarmExpenseView}; this service does not depend on it). */
    public record FarmExpenseView(Long id, java.time.LocalDate date, java.math.BigDecimal amount, String expenseName,
                                  String expenseType, Long landId, String landName, String cropName, String description) { }

    private final AgricultureExpenseRepo rows;

    public InternalFarmHistoryController(AgricultureExpenseRepo rows) {
        this.rows = rows;
    }

    @GetMapping("/internal/agriculture/expenses/not-in-books")
    @Transactional(readOnly = true)
    public List<FarmExpenseView> notInBooks() {
        Long org = CurrentUser.organizationId();
        if (org == null) return List.of();
        return rows.notInBooks(org, PageRequest.of(0, MAX_ROWS)).stream()
                .map(e -> new FarmExpenseView(e.getId(), e.getDated(), e.getAmount(), e.getExpenseName(), e.getExpenseType(),
                        e.getLandId(), e.getLandName(), e.getCropName(), e.getDescription()))
                .toList();
    }

    @PostMapping("/internal/agriculture/expenses/{id}/expense-voucher")
    public void stampExpenseVoucher(@PathVariable("id") Long id, @RequestParam("voucherNo") String voucherNo) {
        Long org = CurrentUser.organizationId();
        if (org == null || voucherNo == null || voucherNo.isBlank() || voucherNo.length() > 20) return;
        rows.stampImported(org, id, voucherNo);
    }
}
