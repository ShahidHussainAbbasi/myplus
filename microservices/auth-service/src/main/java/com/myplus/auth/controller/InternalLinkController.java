package com.myplus.auth.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.auth.dto.ApiResponse;
import com.myplus.auth.service.OrgLinkService;

import lombok.RequiredArgsConstructor;

/**
 * HC-1d R1 — "where do this organisation's prescriptions go?", asked by clinical-service (to show the doctor the right
 * list) and by pharma-service (which re-asks before it writes, never trusting clinical's answer).
 *
 * <h3>Safe even if a signed-in person reaches it</h3>
 * The gateway routes all of {@code /api/auth/**} and stamps the internal secret on every authenticated request it
 * forwards, so the secret proves "came through the gateway or a service", not "is a service". What makes this safe is
 * that the organisation is NEVER a parameter: it is {@code X-Org-Id}, which the gateway strips from the client and
 * stamps from the validated token (and a service forwards from the request it is serving). A caller can learn only its
 * own route. Fails closed (404) with no secret configured or presented, like {@link PortalAccountController}.
 */
@RestController
@RequestMapping("/api/auth/internal/links")
@RequiredArgsConstructor
public class InternalLinkController {

    private final OrgLinkService links;

    @Value("${service.internal-secret:}")
    private String internalSecret;

    @GetMapping("/route")
    public ResponseEntity<ApiResponse<Map<String, Object>>> route(
            @RequestHeader(value = "X-Internal-Secret", required = false) String secret,
            @RequestHeader(value = "X-Org-Id", required = false) Long orgId) {
        if (internalSecret == null || internalSecret.isEmpty() || !internalSecret.equals(secret) || orgId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Not found", HttpStatus.NOT_FOUND.value()));
        }
        return ResponseEntity.ok(ApiResponse.success(links.route(orgId)));
    }
}
