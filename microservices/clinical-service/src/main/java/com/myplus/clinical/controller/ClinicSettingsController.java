package com.myplus.clinical.controller;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.settings.SettingsService;
import com.myplus.common.web.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * HMS S1 — the clinic settings at {@code /api/clinic/settings}: the shared {@code SettingsController} answers at
 * {@code /settings}, which the gateway never routes here, so this thin door delegates to the same engine (the
 * expense-service pattern). Reading is open to every member (the registration form needs the rules); changing is
 * owner/admin.
 */
@RestController
@RequestMapping("/api/clinic/settings")
@RequiredArgsConstructor
public class ClinicSettingsController {

    private final SettingsService settings;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(settings.catalogForOrg().stream()
                .filter(e -> String.valueOf(e.get("key")).startsWith("clinic.")).toList());
    }

    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')")
    @PostMapping
    public ApiResponse<Void> save(@RequestParam String key, @RequestParam(required = false) String value) {
        if (!key.startsWith("clinic.")) return ApiResponse.error("Unknown setting.", 400);
        try {
            settings.set(key, value);
            return ApiResponse.success(null, "Setting saved");
        } catch (IllegalArgumentException bad) {
            return ApiResponse.error(bad.getMessage(), 400);
        }
    }

    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')")
    @PostMapping("/reset")
    public ApiResponse<Void> reset(@RequestParam String key) {
        if (!key.startsWith("clinic.")) return ApiResponse.error("Unknown setting.", 400);
        try {
            settings.reset(key);
            return ApiResponse.success(null, "Setting reset to default");
        } catch (IllegalArgumentException | UnsupportedOperationException bad) {
            return ApiResponse.error(bad.getMessage(), 400);
        }
    }
}
