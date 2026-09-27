package com.myplus.education.controller;

import com.myplus.common.settings.SettingsService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Owner Configuration screen backend — thin adapter over the shared {@link SettingsService} (common-settings).
 * The engine (resolution, org-scoping, catalog aggregation, unknown-key guard) lives in the shared library; this
 * class only translates to education's {@code GenericResponse} envelope so the existing dashboard JS and monolith
 * proxy keep working. The canonical shared REST surface ({@code /settings}, common-web ApiResponse) is also live
 * for anything that prefers it. Reading config is open to any member (behaviour needs it); WRITING is owner-gated.
 */
@Controller
public class SettingsController {

    @Autowired private SettingsService settingsService;   // shared common-settings engine
    @Autowired private AppUtil appUtil;

    @RequestMapping(value = "/getConfig", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getConfig() {
        try {
            return new GenericResponse("SUCCESS", "", settingsService.catalogForOrg());
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Upsert one setting override for the caller's org. Owner-only (changing tenant policy). */
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE')")
    @RequestMapping(value = "/saveConfig", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse saveConfig(@RequestParam String key, @RequestParam(required = false) String value) {
        try {
            settingsService.set(key, value);
            return new GenericResponse("SUCCESS", "Setting saved");
        } catch (IllegalArgumentException bad) {
            return new GenericResponse("INVALID", bad.getMessage());
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * SET-GUIDE — "Reset to default": REMOVE the override so the catalogue default applies again. Not the same as saving
     * the default value, which pins it. Same gate as saving.
     */
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE')")
    @RequestMapping(value = "/resetConfig", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse resetConfig(@RequestParam String key) {
        try {
            settingsService.reset(key);
            return new GenericResponse("SUCCESS", "Setting reset to default");
        } catch (IllegalArgumentException | UnsupportedOperationException bad) {
            return new GenericResponse("INVALID", bad.getMessage());
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }
}
