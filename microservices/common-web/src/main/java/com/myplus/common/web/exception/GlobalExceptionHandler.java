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
        LOG.error("Unhandled exception reached the API boundary", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Something went wrong. Please try again.", 500));
    }
}
