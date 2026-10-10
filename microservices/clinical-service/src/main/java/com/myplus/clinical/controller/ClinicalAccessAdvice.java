package com.myplus.clinical.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.myplus.common.web.ApiResponse;

/**
 * HMS S3a — a refusal in the clinic says WHO may do it ("Only a doctor can open clinical records"), still as a 403.
 * The shared handler answers a bare "Access denied", which tells a receptionist nothing about what to do. Ordered
 * ahead of the shared one, for this service only.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClinicalAccessAdvice {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> denied(AccessDeniedException e) {
        String msg = e.getMessage() == null || e.getMessage().isBlank() || "Access Denied".equalsIgnoreCase(e.getMessage())
                ? "You are not allowed to do this." : e.getMessage();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(msg, 403));
    }
}
