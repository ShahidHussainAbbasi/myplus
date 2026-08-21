package com.myplus.business_service.controller;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.dto.InstallmentPlanViewDTO;
import com.myplus.business_service.entity.InstallmentPlan;
import com.myplus.business_service.service.InstallmentPlanService;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;

/**
 * INST-1 — reading installment plans.
 *
 * <p>Ships WITH the slice rather than after it. A capability the UI cannot reach is review finding R7, which
 * this programme has hit three times — each one a feature built, tested, and reachable by nobody. The
 * monolith proxy lands in the same commit for the same reason.
 *
 * <p>Returns DTOs, never entities: an entity would carry {@code organizationId} and the raw row id to the
 * browser, which §1.5 forbids and which the OMS programme had to fix twice.
 */
@RestController
public class InstallmentController {

    @Autowired private InstallmentPlanService installmentPlanService;
    @Autowired private RequestUtil requestUtil;

    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    /**
     * A customer's plans, newest first — the schedule block on the customer screen.
     *
     * <p>The customer id arrives <b>from the query string</b>, so the read is org-scoped in the repository
     * and the scope is the caller's own. That is the anti-IDOR rule the D2 credit-standing leak established:
     * <b>whether a read needs scoping depends on where the id came from, not on which method reads it.</b> An
     * id followed from a row the caller could already see is safe; an id off the wire is not.
     */
    @GetMapping("/installmentPlans")
    @ResponseBody
    public GenericResponse plansForCustomer(@RequestParam("customerId") Long customerId) {
        LocalDate today = LocalDate.now();
        List<InstallmentPlanViewDTO> out = new ArrayList<>();
        for (InstallmentPlan p : installmentPlanService.plansForCustomer(orgId(), customerId)) {
            out.add(InstallmentPlanViewDTO.of(p, today));
        }
        // Lands in `collection`, not `object`: GenericResponse overloads on Collection vs Object, and a
        // List binds the Collection one. Verified against the live endpoint rather than inferred — the
        // response is {"status":"SUCCESS","collection":[...]}, which is what every list client here expects.
        return new GenericResponse("SUCCESS", "OK", out);
    }

    /**
     * Every plan in the tenant that still owes money, most overdue first — the Installments screen and the
     * collections worklist.
     */
    @GetMapping("/installmentPlansOpen")
    @ResponseBody
    public GenericResponse openPlans() {
        LocalDate today = LocalDate.now();
        List<InstallmentPlanViewDTO> out = new ArrayList<>();
        for (InstallmentPlan p : installmentPlanService.openPlans(orgId(), today)) {
            out.add(InstallmentPlanViewDTO.of(p, today));
        }
        // Lands in `collection`, not `object`: GenericResponse overloads on Collection vs Object, and a
        // List binds the Collection one. Verified against the live endpoint rather than inferred — the
        // response is {"status":"SUCCESS","collection":[...]}, which is what every list client here expects.
        return new GenericResponse("SUCCESS", "OK", out);
    }
}
