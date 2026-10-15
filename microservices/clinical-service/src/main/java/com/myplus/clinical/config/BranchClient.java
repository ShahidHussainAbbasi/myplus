package com.myplus.clinical.config;

import java.util.Map;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * HMS H4 — business-service's stores (the pharmacy branches), as the clinic sees them: only to check that a branch
 * chosen for a doctor is one of THIS business's stores. The caller's identity is forwarded, so the list is the
 * caller's own organisation. Local to clinical-service.
 */
@HttpExchange(accept = "application/json")
public interface BranchClient {

    /** business GenericResponse {status, message, object | collection: [{id, name, ...}]}. */
    @GetExchange("/getStores")
    Map<String, Object> stores();
}
