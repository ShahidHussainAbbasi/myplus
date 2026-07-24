package com.myplus.education.controller;

import com.myplus.education.service.SettingsService;
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
 * Owner Configuration screen backend — the generic per-tenant settings surface. {@code getConfig} returns the
 * whole catalog with each entry's effective value (for the self-rendering UI); {@code saveConfig} upserts one
 * override. Reading config is open to any member (behaviour needs it), but WRITING is owner-gated.
 */
@Controller
public class SettingsController {

    @Autowired private SettingsService settingsService;
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
}
