package com.myplus.business_service.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.service.PayablesSourceService;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.common.web.exception.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * FP-4b — the operator console's view of a tenant's payables switch. The organization travels explicitly (the call
 * carries the OPERATOR's token, whose own org is the platform's); the service refuses anyone but an operator (403).
 */
@RestController
@RequiredArgsConstructor
public class PayablesSourceController {

    private final PayablesSourceService service;
    private final com.myplus.business_service.service.PayablesReconciliationService reconciliation;

    /**
     * FP-6a — a tenant's reconciliation history (newest first) and its clean-days-in-a-row count. Operator only, like
     * the switch. The check runs by itself every day and after every deploy; nothing here is needed to keep it going.
     */
    @GetMapping("/payables-reconciliation")
    public GenericResponse reconciliationHistory(@RequestParam("organizationId") Long organizationId) {
        if (!com.myplus.common.security.CurrentUser.isPlatformOperator())
            return new GenericResponse("ERROR", "Only a platform operator can read this.");
        return new GenericResponse("SUCCESS", "Payables reconciliation", reconciliation.history(organizationId));
    }

    /** FP-6a — check one tenant NOW (the daily job does the same). For a support question or a test; never required. */
    @PostMapping("/payables-reconciliation/run")
    public GenericResponse reconcileNow(@RequestParam("organizationId") Long organizationId) {
        if (!com.myplus.common.security.CurrentUser.isPlatformOperator())
            return new GenericResponse("ERROR", "Only a platform operator can run this.");
        return new GenericResponse("SUCCESS", "Checked", reconciliation.runOrg(organizationId));
    }

    @GetMapping("/payables-source")
    public GenericResponse status(@RequestParam("organizationId") Long organizationId) {
        try {
            return new GenericResponse("SUCCESS", "Payables source", service.status(organizationId));
        } catch (ValidationException refused) {
            return new GenericResponse("ERROR", refused.getMessage());
        }
    }

    @PostMapping("/payables-source")
    public GenericResponse switchTo(@RequestParam("organizationId") Long organizationId,
                                    @RequestParam("source") String source,
                                    @RequestParam(value = "reason", required = false) String reason) {
        try {
            Map<String, Object> out = service.switchTo(organizationId, source, reason);
            return new GenericResponse("SUCCESS", "Supplier balances now read from " + out.get("source"), out);
        } catch (ValidationException refused) {
            return new GenericResponse("ERROR", refused.getMessage());
        }
    }
}
