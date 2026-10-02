package com.myplus.agriculture.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.agriculture.entity.Land;
import com.myplus.agriculture.service.ILandService;
import com.myplus.agriculture.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;

/**
 * EX-2b — the farm's side of the expense-tag SPI ({@code commerce-contracts ExpenseTagClient}): the lands an
 * expense may be tagged to, for THIS caller, scoped exactly as {@code LandController.getUserLand}.
 *
 * <p>Returns the contract's wire shape ({@code [{type, id, label}]}) as plain maps rather than taking a dependency
 * on commerce-contracts for one three-field DTO. expense-service reads it as {@code ExpenseTagView}.
 */
@RestController
public class ExpenseTagController {

    @Autowired
    private ILandService lands;

    @Autowired
    private RequestUtil requestUtil;

    @GetMapping("/expense-tags")
    public List<Map<String, Object>> tags() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        List<Map<String, Object>> out = new ArrayList<>();
        if (u == null || u.getOrganizationId() == null) return out;
        for (Land l : lands.findScoped(u.getOrganizationId(), u.getUserId())) {
            if (l == null || l.getId() == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "LAND");
            m.put("id", l.getId());
            m.put("label", l.getLandName());
            out.add(m);
        }
        return out;
    }
}
