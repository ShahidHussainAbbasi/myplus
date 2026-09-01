package com.web.error;

import com.web.util.GenericResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@ControllerAdvice
public class RestResponseEntityExceptionHandler extends ResponseEntityExceptionHandler {

    @Autowired
    private MessageSource messages;

    public RestResponseEntityExceptionHandler() {
        super();
    }

    // API

    // 400 — @RequestBody / @ModelAttribute @Valid failures. Spring 6 changed the template-method
    // signature to HttpStatusCode; @Override guarantees we actually hook the framework.
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(final MethodArgumentNotValidException ex, final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        logger.error("400 Status Code", ex);
        final BindingResult result = ex.getBindingResult();
        final GenericResponse bodyOfResponse = new GenericResponse(result.getAllErrors(), "Invalid" + result.getObjectName());
        return handleExceptionInternal(ex, bodyOfResponse, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler({ InvalidOldPasswordException.class })
    public ResponseEntity<Object> handleInvalidOldPassword(final RuntimeException ex, final WebRequest request) {
        logger.error("400 Status Code", ex);
        final GenericResponse bodyOfResponse = new GenericResponse(messages.getMessage("message.invalidOldPassword", null, request.getLocale()), "InvalidOldPassword");
        return handleExceptionInternal(ex, bodyOfResponse, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler({ PasswordResetException.class })
    public ResponseEntity<Object> handlePasswordReset(final RuntimeException ex, final WebRequest request) {
        logger.error("400 Status Code", ex);
        // error = code the JS branches on; message = human text it displays.
        final GenericResponse bodyOfResponse = new GenericResponse();
        bodyOfResponse.setStatus("ERROR");
        bodyOfResponse.setError("PasswordResetError");
        bodyOfResponse.setMessage(ex.getMessage());
        return handleExceptionInternal(ex, bodyOfResponse, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    // 404
    @ExceptionHandler({ UserNotFoundException.class })
    public ResponseEntity<Object> handleUserNotFound(final RuntimeException ex, final WebRequest request) {
        logger.error("404 Status Code", ex);
        final GenericResponse bodyOfResponse = new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()), "UserNotFound");
        return handleExceptionInternal(ex, bodyOfResponse, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    // 409
    @ExceptionHandler({ UserAlreadyExistException.class })
    public ResponseEntity<Object> handleUserAlreadyExist(final RuntimeException ex, final WebRequest request) {
        logger.error("409 Status Code", ex);
        final GenericResponse bodyOfResponse = new GenericResponse(messages.getMessage("message.regError", null, request.getLocale()), "UserAlreadyExist");
        return handleExceptionInternal(ex, bodyOfResponse, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    // 500
    @ExceptionHandler({ MailAuthenticationException.class })
    public ResponseEntity<Object> handleMail(final RuntimeException ex, final WebRequest request) {
        logger.error("500 Status Code", ex);
        final GenericResponse bodyOfResponse = new GenericResponse(messages.getMessage("message.email.config.error", null, request.getLocale()), "MailError");
        return new ResponseEntity<Object>(bodyOfResponse, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * 403 — an authorization refusal is an ANSWER, not a server failure.
     *
     * <h3>The defect this closes, found by E2's gate</h3>
     * Without this handler, {@code AccessDeniedException} fell through to {@code handleInternal} below and
     * every {@code @PreAuthorize} refusal in the monolith came back as <b>500 "InternalError"</b>. Three
     * things were wrong with that, in increasing order of seriousness:
     * <ol>
     *   <li>It contradicts standard 8a — the server knew exactly why it refused and threw the reason away.</li>
     *   <li>A caller cannot tell "you may not do this" from "we fell over", so a client retries a refusal.</li>
     *   <li><b>A security event was being logged and reported as a server error.</b> Every unauthorised
     *       attempt looked like a bug, and any real bug hid among them.</li>
     * </ol>
     *
     * <p>Spring Security's {@code ExceptionTranslationFilter} would normally turn this into a 403, but a
     * {@code @ControllerAdvice} catching {@code Exception} intercepts it first — which is why the fix belongs
     * here and not in the security config.
     *
     * <p>Deliberately NOT logged at {@code error}: a refused request is the control working. It is logged at
     * {@code warn} with the path, because a burst of them is worth noticing.
     */
    @ExceptionHandler({ org.springframework.security.access.AccessDeniedException.class })
    public ResponseEntity<Object> handleAccessDenied(final RuntimeException ex, final WebRequest request) {
        logger.warn("403 Access denied: " + request.getDescription(false));
        final GenericResponse bodyOfResponse = new GenericResponse(
                messages.getMessage("message.unauth", null, "Access denied", request.getLocale()), "AccessDenied");
        return new ResponseEntity<Object>(bodyOfResponse, new HttpHeaders(), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler({ Exception.class })
    public ResponseEntity<Object> handleInternal(final RuntimeException ex, final WebRequest request) {
        logger.error("500 Status Code", ex);
        final GenericResponse bodyOfResponse = new GenericResponse(messages.getMessage("message.error", null, request.getLocale()), "InternalError");
        return new ResponseEntity<Object>(bodyOfResponse, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

//    @ExceptionHandler({ Exception.class })
//    public ResponseEntity<Object> alreadyExist(final RuntimeException ex, final WebRequest request) {
//        logger.error("302 Status Code", ex);
//        final GenericResponse bodyOfResponse = new GenericResponse(messages.getMessage("message.hospital.exist", null, request.getLocale()), "HospitalAlreadyExist");
//        return new ResponseEntity<Object>(bodyOfResponse, new HttpHeaders(), HttpStatus.FOUND);
//    }

}
