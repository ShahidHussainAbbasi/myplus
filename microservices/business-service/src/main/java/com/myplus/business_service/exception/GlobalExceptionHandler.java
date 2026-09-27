package com.myplus.business_service.exception;

import com.myplus.business_service.dto.ApiResponse;
import com.myplus.business_service.util.GenericResponse;
// G5/slice-39 dedup: reuse common-web's canonical exception classes (the local copies were deleted). This
// handler stays business-local on purpose — it's monolith-facing (Bean-Validation → HTTP 200 + GenericResponse),
// so CommonWebAutoConfiguration is excluded in BusinessServiceApplication to avoid a second advice.
import com.myplus.common.web.exception.DuplicateResourceException;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

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

    /**
     * E5b — an operator named another business without a support session. Rendered with its SENTENCE and a stable
     * {@code data.code}, not the bare "Access denied" below: the console has to tell "open a support session" from
     * "the service is down". Declared before the general handler for readability; Spring picks the most specific.
     */
    @ExceptionHandler(com.myplus.common.security.SupportSessionRequiredException.class)
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> handleSupportSessionRequired(
            com.myplus.common.security.SupportSessionRequiredException ex) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("code", com.myplus.common.security.SupportSessionRequiredException.CODE);
        data.put("organizationId", ex.getOrganizationId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiResponse<>(false, ex.getMessage(), data, 403));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Access denied", 403));
    }

    // Bean Validation failures on the flat (monolith-facing) endpoints. MethodArgumentNotValidException
    // (@RequestBody) extends BindException (@ModelAttribute/form), so this one handler covers both.
    // Returns the flat GenericResponse("ERROR", …) envelope at HTTP 200 so the monolith JS shows the
    // message via its normal error path (instead of a raw 400/500 the UI can't read).
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public GenericResponse handleValidationErrors(BindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return new GenericResponse("ERROR", message.isEmpty() ? "Validation failed" : message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Internal server error: " + ex.getMessage(), 500));
    }
}
