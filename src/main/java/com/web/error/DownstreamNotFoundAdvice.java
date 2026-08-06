package com.web.error;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
/*
 * @Order is LOAD-BEARING — without it this advice never runs.
 *
 * {@code RestResponseEntityExceptionHandler} declares @ExceptionHandler({Exception.class}), which matches
 * everything. Spring's ExceptionHandlerExceptionResolver walks advice beans IN ORDER and returns the FIRST
 * one that has any matching method — it does NOT choose the most specific handler across advices. With
 * neither advice ordered, both sit at LOWEST_PRECEDENCE and registration order decides, so the catch-all
 * won and every downstream 404 still came back as a 500. That is exactly how this shipped on 2026-08-06 and
 * what the portal gate caught.
 *
 * Worth knowing more generally: that catch-all outranks any unordered advice trying to handle something
 * more specifically. This is the same swallowing behaviour slice 2.1 recorded as standard D3d.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
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
