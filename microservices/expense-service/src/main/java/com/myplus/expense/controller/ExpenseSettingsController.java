package com.myplus.expense.controller;

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
 * EX-2f — the expense settings, at {@code /api/expense/settings}. The shared {@code SettingsController} answers at
 * {@code /settings}, which the gateway never routes here (it forwards {@code /api/expense/**} unchanged), so this
 * thin door delegates to the same {@link SettingsService}: one engine, one validation, one audit path.
 * Reading is open to every member (the form needs the default); changing is owner/admin.
 */
@RestController
@RequestMapping("/api/expense/settings")
@RequiredArgsConstructor
public class ExpenseSettingsController {

    private final SettingsService settings;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(settings.catalogForOrg().stream()
                .filter(e -> String.valueOf(e.get("key")).startsWith("expense.")).toList());
    }

    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')")
    @PostMapping
    public ApiResponse<Void> save(@RequestParam String key, @RequestParam(required = false) String value) {
        if (!key.startsWith("expense.")) return ApiResponse.error("Unknown setting.", 400);
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
        if (!key.startsWith("expense.")) return ApiResponse.error("Unknown setting.", 400);
        try {
            settings.reset(key);
            return ApiResponse.success(null, "Setting reset to default");
        } catch (IllegalArgumentException | UnsupportedOperationException bad) {
            return ApiResponse.error(bad.getMessage(), 400);
        }
    }
}
