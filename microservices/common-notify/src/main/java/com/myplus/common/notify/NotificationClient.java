package com.myplus.common.notify;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * Send and have it RECORDED — slice 105.
     *
     * <p>Added alongside the original rather than replacing it, so all thirteen existing callers keep
     * compiling and behaving identically and each can adopt attribution when its own slice comes round.
     * Expand now, contract later; a breaking change to a shared contract would have forced every consuming
     * service to move in one commit.
     *
     * @param source    which module asked, in its own words ("EDU-ALERT", "EDU-COVER") — for the audit trail
     * @param orgId     the tenant this message belongs to; without it the record cannot be read back, since
     *                  the read endpoints are tenant-scoped
     * @param dedupeKey idempotency key. A relay that re-POSTs after a timeout must not send twice — and
     *                  because both sides retry, that is a certainty rather than a risk.
     */
    @PostExchange("/email")
    Boolean sendEmail(@RequestBody EmailRequest request,
                      @RequestParam(name = "source", required = false) String source,
                      @RequestParam(name = "orgId", required = false) Long orgId,
                      @RequestParam(name = "dedupeKey", required = false) String dedupeKey);
}
