package com.myplus.auth.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.auth.dto.ApiResponse;
import com.myplus.auth.service.OrgLinkService;
import com.myplus.common.security.CurrentUser;

import lombok.RequiredArgsConstructor;

/**
 * HC-1c — the clinic ↔ pharmacy link. Owner / admin of the CALLING business only; the business is always the token's
 * ({@link CurrentUser#organizationId()}), never a parameter. Every rule lives in {@link OrgLinkService}.
 * A refusal answers 200 + {@code success:false} with a stable {@code reason} code (the platform's convention for a
 * business refusal) — the screen shows that code in the person's language.
 */
@RestController
@RequestMapping("/api/auth/links")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE')")
public class OrgLinkController {

    private final OrgLinkService links;

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> settings() {
        return ResponseEntity.ok(ApiResponse.success(Map.of("acceptClinics", links.acceptsClinics(CurrentUser.organizationId())), "Link settings"));
    }

    @PostMapping("/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveSettings(@RequestBody Map<String, Object> body) {
        boolean on = body != null && Boolean.parseBoolean(String.valueOf(body.get("acceptClinics")));
        return ResponseEntity.ok(ApiResponse.success(Map.of("acceptClinics",
                links.setAcceptsClinics(CurrentUser.organizationId(), CurrentUser.userId(), on)), "Saved"));
    }

    @GetMapping("/pharmacies")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> pharmacies(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(ApiResponse.success(links.findPharmacies(CurrentUser.organizationId(), q), "Pharmacies"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> mine() {
        return ResponseEntity.ok(ApiResponse.success(links.mine(CurrentUser.organizationId()), "Links"));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> request(@RequestBody Map<String, Object> body) {
        Long pharmacy = toLong(body == null ? null : body.get("pharmacyOrgId"));
        return ResponseEntity.ok(ApiResponse.success(
                links.request(CurrentUser.organizationId(), CurrentUser.userId(), pharmacy), "Link requested"));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<ApiResponse<Map<String, Object>>> accept(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(links.accept(CurrentUser.organizationId(), CurrentUser.userId(), id), "Linked"));
    }

    @PostMapping("/{id}/end")
    public ResponseEntity<ApiResponse<Map<String, Object>>> end(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return ResponseEntity.ok(ApiResponse.success(links.end(CurrentUser.organizationId(), CurrentUser.userId(), id, reason), "Link ended"));
    }

    @ExceptionHandler(OrgLinkService.LinkRefusedException.class)
    public ResponseEntity<ApiResponse<Object>> refused(OrgLinkService.LinkRefusedException e) {
        return ResponseEntity.ok(ApiResponse.refused(e.getCode(), e.getMessage(), 409));
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }
}
