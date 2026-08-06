package com.myplus.commerce.contracts.client;

import java.util.Map;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Slice 3.1b — provisioning the sign-in account behind a portal invitation.
 * Design: microservices/docs/slices/edu-3.1b-portal-sign-in.md
 *
 * <p>Contract only (DIP): the proxy is built from a load-balanced RestClient in each consuming service,
 * against {@code lb://auth-service/api/auth/portal}. Identity and the internal secret are stamped by
 * {@code GatewayIdentityForwarding.interceptor()}, which is what authorises the call on the auth side.
 *
 * <p>Deliberately narrow. Education must be able to open and close a portal login and nothing else — it
 * cannot read accounts, list them, change roles or set a password. Identity stays auth-service's to own;
 * this is the seam, not a window into it.
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface AuthAccountClient {

    /**
     * Create or link the portal account for {@code email} in {@code organizationId}, and send its
     * set-password email. Idempotent: re-inviting an existing guardian re-sends the invitation rather than
     * failing, which is what "resend" means to the person clicking it.
     *
     * <p>Body: {@code {email, organizationId, role}} — role is the MEMBERSHIP role (GUARDIAN | STUDENT).
     */
    @PostExchange("/account")
    Map<String, Object> createOrLink(@RequestBody Map<String, Object> request);

    /** Disable the sign-in for {@code email}. The account and its membership are kept, never deleted. */
    @PostExchange("/account/disable")
    Map<String, Object> disable(@RequestBody Map<String, Object> request);
}
