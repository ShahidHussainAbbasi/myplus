package com.myplus.business_service.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.service.IVenderService;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.dto.ExpenseTagView;
import com.myplus.common.security.AuthenticatedUser;

/**
 * FP-3 — the shop's side of the expense-tag SPI ({@code commerce-contracts ExpenseTagClient}): the SUPPLIERS an
 * expense BILL may be owed to, for THIS caller.
 *
 * <h3>The same list the Suppliers screen shows</h3>
 * {@code findScoped(org, user)} is exactly what {@code /getUserVender} reads, so a cashier is offered the suppliers
 * they already see and nothing else. expense-service confirms a bill's supplier against THIS list with the caller's
 * identity, so a foreign or invented supplier id can never be stored on a bill.
 */
@RestController
public class ExpenseTagController {

    @Autowired private IVenderService venders;
    @Autowired private RequestUtil requestUtil;

    @GetMapping("/expense-tags")
    @Transactional(readOnly = true)
    public List<ExpenseTagView> tags() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        List<ExpenseTagView> out = new ArrayList<>();
        if (u == null || u.getOrganizationId() == null) return out;
        for (Vender v : venders.findScoped(u.getOrganizationId(), u.getUserId())) {
            if (v != null && v.getId() != null && v.getName() != null) out.add(new ExpenseTagView("SUPPLIER", v.getId(), v.getName()));
        }
        return out;
    }
}
