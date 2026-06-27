package com.myplus.business_service.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.business_service.dto.TaxSettingDTO;
import com.myplus.business_service.entity.TaxSetting;
import com.myplus.business_service.service.TaxService;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;

/**
 * Org tax policy (G3 tax engine, slice 35). One setting per tenant — get the current policy (or a disabled
 * default) and upsert it. org/user come from the propagated gateway identity.
 */
@Controller
public class TaxSettingController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    TaxService taxService;

    @Autowired
    RequestUtil requestUtil;

    private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u == null ? null : u.getUserId(); }
    private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u == null ? null : u.getOrganizationId(); }

    @RequestMapping(value = "/getTaxSetting", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getTaxSetting() {
        try {
            TaxSetting s = taxService.settingsFor(orgId());
            return new GenericResponse("SUCCESS", null, s);
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > getTaxSetting " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not load tax settings.");
        }
    }

    @RequestMapping(value = "/saveTaxSetting", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse saveTaxSetting(final TaxSettingDTO dto) {
        try {
            TaxSetting saved = taxService.saveSetting(orgId(), userId(), dto);
            return new GenericResponse("SUCCESS", "Tax settings saved.", saved);
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > saveTaxSetting " + e.getMessage(), e);
            return new GenericResponse("FAILED", "Could not save tax settings.");
        }
    }
}
