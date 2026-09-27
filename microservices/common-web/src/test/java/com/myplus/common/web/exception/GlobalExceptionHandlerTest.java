package com.myplus.common.web.exception;

import com.myplus.common.web.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BLK-0 — a refusal the framework already classified keeps its status; everything else is still a 500.
 *
 * <p>Found by BLK-0's gate: a direct {@code POST /api/finance/payments} was correctly refused by Spring (no
 * handler ran, nothing was written) and the caller was told {@code 500 "Something went wrong"}. A 500 is also
 * what a write that crashed half-way returns, so while both answer 500 no check can tell a shut door from a
 * broken one.
 *
 * <p>Asserts the STATUS each case answers, not the log line it writes. The log level is part of the design
 * (4xx → WARN, 5xx → ERROR), but asserting it would depend on which logging backend is on this module's test
 * classpath, and a test that passes or fails with the classpath proves nothing about the handler.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * E5b — the console tells "open a support session" apart from "the service is down" by data.code; the bare
     * "Access denied" handler would have dropped both the sentence and the code.
     */
    @Test
    void anOperatorWithoutASupportSession_isA403_withTheSentenceAndTheCode() {
        ResponseEntity<ApiResponse<java.util.Map<String, Object>>> r = handler.handleSupportSessionRequired(
                new com.myplus.common.security.SupportSessionRequiredException(49L));
        assertEquals(403, r.getStatusCode().value());
        assertNotNull(r.getBody());
        assertFalse(r.getBody().isSuccess());
        assertTrue(r.getBody().getMessage().contains("support session"), r.getBody().getMessage());
        assertEquals("SUPPORT_SESSION_REQUIRED", r.getBody().getData().get("code"));
        assertEquals(49L, r.getBody().getData().get("organizationId"));
    }

    @Test
    void aPostToAGetOnlyPath_isA405_carryingAllow_notA500() {
        ResponseEntity<ApiResponse<Void>> r = handler.handleGeneric(
                new HttpRequestMethodNotSupportedException("POST", List.of("GET")));

        assertEquals(405, r.getStatusCode().value(), "the refusal keeps its own status");
        assertTrue(r.getHeaders().getAllow().contains(HttpMethod.GET),
                "Allow is what tells an API client what the path does accept");
        assertNotNull(r.getBody());
        assertFalse(r.getBody().isSuccess());
        assertEquals(405, r.getBody().getStatusCode(), "the envelope agrees with the HTTP status");
    }

    @Test
    void theBodyIsTheReasonPhrase_neverTheFrameworkDetail() {
        ResponseEntity<ApiResponse<Void>> r = handler.handleGeneric(
                new HttpRequestMethodNotSupportedException("POST", List.of("GET")));

        assertEquals("Method Not Allowed", r.getBody().getMessage());
    }

    @Test
    void aMissingRequestParameter_isA400() {
        ResponseEntity<ApiResponse<Void>> r = handler.handleGeneric(
                new MissingServletRequestParameterException("partyId", "Long"));

        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void aFramework5xx_keepsItsOwnStatus_andIsNotFlattenedTo500() {
        // AsyncRequestTimeoutException is Spring's 503. It must stay a 503 — and it takes the ERROR path, because
        // a server-side failure must never be quietly downgraded to a one-line warning.
        ResponseEntity<ApiResponse<Void>> r = handler.handleGeneric(new AsyncRequestTimeoutException());

        assertEquals(503, r.getStatusCode().value());
    }

    @Test
    void anyOtherException_isStillA500_andTheBodyNeverEchoesInternals() {
        ResponseEntity<ApiResponse<Void>> r = handler.handleGeneric(
                new IllegalStateException("Duplicate entry '13-958' for key 'uq_ch_org_invoice_seq'"));

        assertEquals(500, r.getStatusCode().value(), "nothing but a framework-classified refusal changes status");
        assertEquals("Something went wrong. Please try again.", r.getBody().getMessage(),
                "SQL, constraint names and row values belong in the log, not in the response");
    }
}
