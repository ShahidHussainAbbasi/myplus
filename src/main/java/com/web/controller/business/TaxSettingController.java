package com.web.controller.business;

import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.BusinessRestClient;

/** Proxy for the org tax policy (G3 tax engine, slice 35) → business-service. */
@Controller
public class TaxSettingController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/getTaxSetting", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getTaxSetting(final HttpServletRequest request) {
        try {
            return client.get("/getTaxSetting");
        } catch (Exception e) {
            LOGGER.error("getTaxSetting proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/saveTaxSetting", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> saveTaxSetting(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/saveTaxSetting", params);
        } catch (Exception e) {
            LOGGER.error("saveTaxSetting proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }
}
