package com.web.controller.business;

import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.BusinessRestClient;

@Controller
public class ItemUnitController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/getUserItemUnit", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserItemUnit(final HttpServletRequest request) {
        try {
            return client.get("/getUserItemUnit");
        } catch (Exception e) {
            LOGGER.error("getUserItemUnit proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getUserItemUnits", method = RequestMethod.GET)
    @ResponseBody
    public String getUserItemUnits(final HttpServletRequest request) {
        try {
            return client.getString("/getUserItemUnits");
        } catch (Exception e) {
            LOGGER.error("getUserItemUnits proxy error", e);
            return "<option value=''> Item not available </option>";
        }
    }

    @RequestMapping(value = "/getAllItemUnit", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getAllItemUnit(final HttpServletRequest request) {
        try {
            return client.get("/getAllItemUnit");
        } catch (Exception e) {
            LOGGER.error("getAllItemUnit proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/addItemUnit", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addItemUnit(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/addItemUnit", params);
        } catch (Exception e) {
            LOGGER.error("addItemUnit proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteItemUnit", method = RequestMethod.POST)
    @ResponseBody
    public Boolean deleteItemUnit(HttpServletRequest req, HttpServletResponse resp) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("checked", req.getParameter("checked"));
            return client.postFormBoolean("/deleteItemUnit", params);
        } catch (Exception e) {
            LOGGER.error("deleteItemUnit proxy error", e);
            return false;
        }
    }
}
