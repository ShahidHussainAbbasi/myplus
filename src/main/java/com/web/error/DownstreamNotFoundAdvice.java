package com.web.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Slice 3.1b — relays a downstream {@code 404} to the browser AS a 404.
 *
 * <p>Without this, {@link DownstreamNotFoundException} reaches the catch-all handler and becomes a generic
 * {@code 500} "Error Occurred" — which is untrue, and for the guardian portal actively harmful: the whole
 * point of {@code PortalScopeFilter} answering 404 is that a portal caller learns nothing, and a 500 tells
 * them something happened.
 *
 * <p>The downstream body is passed through unchanged when there is one, so a service's own message is never
 * invented over; an empty body stays empty, which is the correct answer for a refusal that is meant to be
 * silent.
 */
@RestControllerAdvice
public class DownstreamNotFoundAdvice {

    @ExceptionHandler(DownstreamNotFoundException.class)
    @ResponseBody
    public ResponseEntity<String> handle(DownstreamNotFoundException ex) {
        String body = ex.getBody();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body == null || body.isBlank() ? "" : body);
    }
}
