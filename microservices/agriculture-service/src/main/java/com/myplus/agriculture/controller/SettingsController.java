package com.myplus.agriculture.controller;

import com.myplus.agriculture.util.AppUtil;
import com.myplus.agriculture.util.GenericResponse;
import com.myplus.common.settings.SettingsService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Owner Configuration screen backend — thin adapter over the shared {@link SettingsService} (common-settings).
 * The engine lives in the shared library; this only translates to agriculture's {@code GenericResponse} envelope
 * so the dashboard JS + monolith proxy match the education/business/welfare pattern. Reading config is open to any
 * member (behaviour needs it); WRITING is owner-gated.
 */
@Controller
public class SettingsController {

    @Autowired private SettingsService settingsService;   // shared common-settings engine
    @Autowired private AppUtil appUtil;

    @RequestMapping(value = "/getConfig", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getConfig() {
        try {
            return new GenericResponse(appUtil.SUCCESS, "", settingsService.catalogForOrg());
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse(appUtil.ERROR, e.getMessage());
        }
    }

    /** Upsert one setting override for the caller's org. Owner-only (changing tenant policy). */
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    @RequestMapping(value = "/saveConfig", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse saveConfig(@RequestParam String key, @RequestParam(required = false) String value) {
        try {
            settingsService.set(key, value);
            return new GenericResponse(appUtil.SUCCESS, "Setting saved");
        } catch (IllegalArgumentException bad) {
            return new GenericResponse(appUtil.INVALID, bad.getMessage());
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse(appUtil.ERROR, e.getMessage());
        }
    }
}
