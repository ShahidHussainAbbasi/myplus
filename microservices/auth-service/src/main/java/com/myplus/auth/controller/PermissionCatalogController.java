package com.myplus.auth.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.auth.dto.ApiResponse;
import com.myplus.auth.entity.Permission;
import com.myplus.auth.repository.EndpointPermissionRepository;
import com.myplus.auth.repository.PermissionRepository;

import lombok.RequiredArgsConstructor;

/**
 * RW-0c (2026-10-12) — the permission catalogue as data for the other parts of the platform.
 *
 * <ul>
 *   <li>{@code /api/auth/org/endpoint-rules} — the monolith's endpoint rules (V26) with each code's LABEL, so a
 *       refusal quotes the catalogue ("Receive stock") instead of a sentence written in code. Signed-in members.</li>
 *   <li>{@code /api/auth/catalog/permission-codes} — every permission code, nothing else. PUBLIC: a service checks at
 *       start-up that each code it names exists (PermissionCatalogCheck), before any user is signed in. The codes are
 *       the product's vocabulary, not anyone's data.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class PermissionCatalogController {

    private final EndpointPermissionRepository endpointRules;
    private final PermissionRepository permissions;

    @GetMapping("/api/auth/org/endpoint-rules")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> endpointRules() {
        Map<String, String> labelOf = permissions.findAll().stream()
                .collect(Collectors.toMap(Permission::getCode, p -> p.getLabel() == null ? p.getCode() : p.getLabel(),
                        (a, b) -> a));
        List<Map<String, Object>> out = endpointRules.findAllByOrderBySortOrderAsc().stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", r.getHttpMethod());
            row.put("path", r.getPathPrefix());
            row.put("code", r.getPermissionCode());
            row.put("label", labelOf.getOrDefault(r.getPermissionCode(), r.getPermissionCode()));
            return row;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(out, "Endpoint rules"));
    }

    @GetMapping("/api/auth/catalog/permission-codes")
    public ResponseEntity<ApiResponse<List<String>>> permissionCodes() {
        return ResponseEntity.ok(ApiResponse.success(
                permissions.findAll().stream().map(Permission::getCode).sorted().toList(), "Permission codes"));
    }
}
