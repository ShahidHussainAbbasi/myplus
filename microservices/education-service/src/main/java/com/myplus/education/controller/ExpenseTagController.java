package com.myplus.education.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.commerce.contracts.dto.ExpenseTagView;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.School;
import com.myplus.education.entity.Vehicle;
import com.myplus.education.repository.SchoolRepository;
import com.myplus.education.repository.VehicleRepository;
import com.myplus.education.util.RequestUtil;

/**
 * EX-2b — the school's side of the expense-tag SPI ({@code commerce-contracts ExpenseTagClient}): the schools
 * (branches) and vehicles an expense may be tagged to, for THIS caller.
 *
 * <h3>Visibility is the platform's one rule, not a copy</h3>
 * Each row passes {@code RequestUtil.canAccessSchool} — {@code LocationScope.canAccess}, the anti-IDOR rule every
 * branch-scoped screen uses. A teacher granted one branch is offered that branch and its buses, nothing else; and
 * because expense-service confirms a tag against THIS list, an id outside it can never be stored.
 */
@RestController
public class ExpenseTagController {

    @Autowired private SchoolRepository schools;
    @Autowired private VehicleRepository vehicles;
    @Autowired private RequestUtil requestUtil;

    @GetMapping("/expense-tags")
    @Transactional(readOnly = true)
    public List<ExpenseTagView> tags() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        List<ExpenseTagView> out = new ArrayList<>();
        if (u == null || u.getOrganizationId() == null) return out;
        Long org = u.getOrganizationId(), user = u.getUserId();
        for (School s : schools.findScoped(org, user)) {
            if (s != null && s.getId() != null && requestUtil.canAccessSchool(s.getId())) {
                out.add(new ExpenseTagView("SCHOOL", s.getId(), s.getName()));
            }
        }
        for (Vehicle v : vehicles.findScoped(org, user)) {
            if (v != null && v.getId() != null && requestUtil.canAccessSchool(v.getSchoolId())) {
                String label = v.getName() == null ? String.valueOf(v.getNumber())
                        : v.getName() + (v.getNumber() == null ? "" : " (" + v.getNumber() + ")");
                out.add(new ExpenseTagView("VEHICLE", v.getId(), label));
            }
        }
        return out;
    }
}
