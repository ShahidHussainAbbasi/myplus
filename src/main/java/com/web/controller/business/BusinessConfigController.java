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

/**
 * Owner Configuration screen proxy → business-service's shared common-settings endpoint ({@code /settings}).
 * The catalog + per-org values (GET) and one override upsert (POST) both live at {@code /api/business/settings}
 * — the shared controller keys on the HTTP verb, so the monolith exposes distinct paths that map to each.
 */
@Controller
public class BusinessConfigController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    /** The settings catalog with each entry's effective value for this org (self-rendering screen reads it). */
    @RequestMapping(value = "/getBusinessConfig", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getBusinessConfig(final HttpServletRequest request) {
        try {
            return client.get("/settings");
        } catch (Exception e) {
            LOGGER.error("getBusinessConfig proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** Upsert one override (key + value). Owner/admin — the server @PreAuthorize is the real gate. */
    @RequestMapping(value = "/saveBusinessConfig", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> saveBusinessConfig(final HttpServletRequest request) {
        try {
            String key = request.getParameter("key");
            String value = request.getParameter("value");
            // The shared endpoint reads key/value as query params (@RequestParam), so pass them on the URL.
            String qs = "key=" + enc(key) + (value != null ? "&value=" + enc(value) : "");
            return client.postJson("/settings?" + qs, java.util.Map.of());
        } catch (Exception e) {
            LOGGER.error("saveBusinessConfig proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    private static String enc(String s) {
        return s == null ? "" : java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
