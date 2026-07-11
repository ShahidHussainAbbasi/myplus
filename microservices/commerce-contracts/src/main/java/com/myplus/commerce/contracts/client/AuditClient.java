package com.myplus.commerce.contracts.client;

import com.myplus.commerce.contracts.dto.AuditEventRequest;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Declarative client for the standalone audit-service. Any producer emits audit events here (via its transactional
 * outbox + relay) without coupling to audit internals. The implementing proxy is built from a load-balanced RestClient
 * (base URL {@code lb://audit-service/api/audit}) in the consuming service (see TradeClientsConfig).
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface AuditClient {

    /** Append one audit event (idempotent on eventKey in audit-service). */
    @PostExchange("/record")
    void record(@RequestBody AuditEventRequest request);
}
