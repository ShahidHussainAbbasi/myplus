package com.myplus.common.settings;

import com.myplus.common.web.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The shared Configuration API — one per service, mounted at {@code /settings} (reached via each service's
 * gateway route, e.g. {@code /api/business/settings}). Reading the catalog is open to any member (behaviour
 * needs it); writing an override is owner/admin only.
 *
 * Registered by {@code CommonSettingsAutoConfiguration} (@Import); the consuming service supplies the
 * {@link SettingsService}'s collaborators (a {@link SettingsStore} + catalog providers).
 */
@RestController
@RequestMapping("/settings")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    /** The catalog with each entry's effective value for the caller's org (feeds the self-rendering screen). */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(settings.catalogForOrg());
    }

    /** Upsert one override for the caller's org. Owner/admin only. Unknown keys are rejected (400-style). */
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE')")
    @PostMapping
    public ApiResponse<Void> save(@RequestParam String key, @RequestParam(required = false) String value) {
        try {
            settings.set(key, value);
            return ApiResponse.success(null, "Setting saved");
        } catch (IllegalArgumentException bad) {
            return ApiResponse.error(bad.getMessage(), 400);
        }
    }
}
