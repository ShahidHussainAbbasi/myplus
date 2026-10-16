package com.myplus.clinical.config;

import java.util.Map;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * HC-1d R1 — auth-service's answer to "where do my doctors' prescriptions go?" for the CALLER's organisation (the
 * forwarded X-Org-Id; it is never a parameter): {@code {route: SAME_ORG | LINKED | NONE, pharmacyName}}. Local to
 * clinical-service. pharma-service asks the same question itself before it writes, so the two cannot disagree.
 */
@HttpExchange(accept = "application/json")
public interface LinkRouteClient {

    @GetExchange("/route")
    Map<String, Object> route();
}
