package com.myplus.common.notify;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Declarative client for notification-service (slice 33, Phase 8). The proxy is built from a load-balanced
 * RestClient (base {@code lb://notification-service/api/notifications}) in each consuming service; this is the
 * contract only. {@code sendEmail} returns true when accepted/sent (best-effort on the provider side).
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface NotificationClient {

    @PostExchange("/email")
    Boolean sendEmail(@RequestBody EmailRequest request);
}
