package com.myplus.auth.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.auth.dto.ApiResponse;
import com.myplus.auth.service.BusinessTypeService;

import lombok.RequiredArgsConstructor;

/**
 * HC-1a — the product lines and business types (V35 rows). Not secret, but not public yet either: its one caller
 * today is the operator console. Self-signup reading it is the follow-on (HC-1a-2) and will need its own door.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class BusinessTypeController {

    private final BusinessTypeService businessTypes;

    @GetMapping("/business-types")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> businessTypes() {
        return ResponseEntity.ok(ApiResponse.success(businessTypes.catalogue(), "Business types"));
    }
}
