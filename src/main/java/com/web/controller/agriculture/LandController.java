package com.web.controller.agriculture;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.AgricultureRestClient;

@Controller
public class LandController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private AgricultureRestClient client;

    private Map<String, String> params(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
        return params;
    }

    @RequestMapping(value = "/addLand", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addLand(final HttpServletRequest request) {
        try {
            return client.postForm("/addLand", params(request));
        } catch (Exception e) {
            LOGGER.error("addLand proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getUserLand", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserLand(final HttpServletRequest request) {
        try {
            return client.get("/getUserLand");
        } catch (Exception e) {
            LOGGER.error("getUserLand proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getUserLands", method = RequestMethod.GET)
    @ResponseBody
    public String getUserLands(final HttpServletRequest request) {
        try {
            return client.getString("/getUserLands");
        } catch (Exception e) {
            LOGGER.error("getUserLands proxy error", e);
            return "<option value=''>No Data found</option>";
        }
    }

    @RequestMapping(value = "/getAllLand", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getAllLand(final HttpServletRequest request) {
        try {
            return client.get("/getAllLand");
        } catch (Exception e) {
            LOGGER.error("getAllLand proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteLand", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> deleteLand(final HttpServletRequest request) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("checked", request.getParameter("checked"));
            return client.postForm("/deleteLand", params);
        } catch (Exception e) {
            LOGGER.error("deleteLand proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }
}
