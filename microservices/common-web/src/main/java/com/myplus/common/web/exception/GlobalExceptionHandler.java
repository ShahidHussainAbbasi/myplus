package com.myplus.common.web.exception;

import com.myplus.common.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.stream.Collectors;

/**
 * Shared REST exception-to-envelope mapping (slice 33, Phase 1). Registered via
 * {@link com.myplus.common.web.CommonWebAutoConfiguration} for any service that opts in by adding the
 * common-web dependency, replacing the per-service copies in inventory/pharma/analytics/marketplace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ex.getMessage(), 404));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateResourceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage(), 409));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(ValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(ex.getMessage(), 400));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Access denied", 403));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationErrors(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(message, 400));
    }

    /**
     * OMS O2 — two people changed the same record at once. That is a <b>conflict</b>, not a server fault.
     *
     * <p>O2 put {@code @Version} on {@code Order} so a concurrent edit could not silently overwrite another,
     * and then left the failure falling through to {@link #handleGeneric}: the client got
     * {@code 500 Internal server error}, which says "we are broken" about the one case where nothing is broken
     * and the right answer is "reload and try again". A 500 is also the status clients are told never to retry,
     * so the guard worked and the response told the caller to give up.
     *
     * <p>Catches the {@code OptimisticLockingFailureException} base type, not just Hibernate's
     * {@code ObjectOptimisticLockingFailureException} subclass, so a lock conflict raised by any Spring Data
     * module maps the same way.
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(
            org.springframework.dao.OptimisticLockingFailureException ex) {
        LOG.info("Concurrent update rejected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                "Someone else changed this while you were working on it. Reload and try again.", 409));
    }

    /**
     * The last resort.
     *
     * <p>Two things this deliberately does that the original did not:
     *
     * <ul>
     *   <li><b>Logs.</b> It previously logged nothing at all, in every service using common-web — so the only
     *       record of an unexpected failure was the sentence handed to the browser. That is D3d's incident
     *       ({@code GatewayClient} discarding downstream errors) repeated one layer up.</li>
     *   <li><b>Stops echoing {@code ex.getMessage()} to the caller.</b> An unhandled exception's message is
     *       written for an engineer, not a customer: it carries SQL fragments, constraint and class names, and
     *       occasionally row values. The detail belongs in the log, which is now where it goes.</li>
     * </ul>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse refusal) {
            return frameworkStatus(ex, refusal);
        }
        LOG.error("Unhandled exception reached the API boundary", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Something went wrong. Please try again.", 500));
    }

    /**
     * BLK-0 — a failure Spring itself already classified is answered with ITS status, not flattened to 500.
     *
     * <h3>What this was doing wrong</h3>
     * Every framework refusal — a POST to a GET-only path (405), a missing request parameter (400), an unknown
     * path (404), an unsupported content type (415) — fell into the catch-all above and went back as
     * {@code 500 "Something went wrong"}, logged at ERROR as an unhandled failure. Found by BLK-0's gate: after
     * the ledger write moved to {@code /internal/**}, a direct {@code POST /api/finance/payments} was correctly
     * REFUSED by Spring's handler mapping — nothing ran, nothing was written — and the caller was told the
     * server had crashed. A refusal reported as a fault is the same defect the {@code AccessDeniedException}
     * mapping above fixed for authorization, one layer down.
     *
     * <p>It also blinds the checks that matter: a 500 is what a write that crashed half-way returns too, so a
     * gate cannot tell "the door is shut" from "the door is broken" while both answer 500.
     *
     * <h3>One branch, not a list of exception classes</h3>
     * Spring 6's own exceptions that know their status implement {@link org.springframework.web.ErrorResponse}.
     * {@code @ExceptionHandler} cannot target an interface, so the check lives here, and covers every such
     * exception — including ones added by a future Spring version — without anyone having to enumerate them.
     * Nothing in this repo throws one of these itself (verified 2026-09-14), so only refusals raised by the
     * framework change status.
     *
     * <h3>Three details that are the point</h3>
     * <ul>
     *   <li><b>The log level follows the STATUS, not the branch.</b> A 4xx is the caller's mistake: one WARN line,
     *       no stack. A 5xx that arrives this way — {@code AsyncRequestTimeoutException} is a 503 — is still a
     *       server-side failure and keeps the ERROR log with its stack, or a real outage would be quietly
     *       downgraded to a warning.</li>
     *   <li><b>The framework's headers go back with it</b> — a 405 carries {@code Allow: GET}, which is what tells
     *       an API client what the path does accept.</li>
     *   <li><b>The body is the reason phrase, never {@code getBody().getDetail()}.</b> The detail can carry
     *       internals, which is the same reason the catch-all stopped echoing {@code ex.getMessage()}.</li>
     * </ul>
     */
    private ResponseEntity<ApiResponse<Void>> frameworkStatus(Exception ex, org.springframework.web.ErrorResponse refusal) {
        org.springframework.http.HttpStatusCode status = refusal.getStatusCode();
        int code = status.value();
        if (status.is4xxClientError()) {
            LOG.warn("Request refused with {}: {}", code, ex.getMessage());
        } else {
            LOG.error("Framework failure reached the API boundary with {}", code, ex);
        }
        HttpStatus known = HttpStatus.resolve(code);
        String reason = known != null ? known.getReasonPhrase() : "Request failed";
        return ResponseEntity.status(status)
                .headers(refusal.getHeaders())
                .body(ApiResponse.error(reason, code));
    }
}
