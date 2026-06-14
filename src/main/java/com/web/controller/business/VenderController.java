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
public class VenderController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/getUserVender", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserVender(final HttpServletRequest request) {
        try {
            return client.get("/getUserVender");
        } catch (Exception e) {
            LOGGER.error("getUserVender proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getUserVenders", method = RequestMethod.GET)
    @ResponseBody
    public String getUserVenders(final HttpServletRequest request) {
        try {
            return client.getString("/getUserVenders");
        } catch (Exception e) {
            LOGGER.error("getUserVenders proxy error", e);
            return "<option value=''>No Data found</option>";
        }
    }

    @RequestMapping(value = "/getAllVender", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getAllVender(final HttpServletRequest request) {
        try {
            return client.get("/getAllVender");
        } catch (Exception e) {
            LOGGER.error("getAllVender proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/addVender", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addVender(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/addVender", params);
        } catch (Exception e) {
            LOGGER.error("addVender proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteVender", method = RequestMethod.POST)
    @ResponseBody
    public Boolean deleteVender(HttpServletRequest req, HttpServletResponse resp) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("checked", req.getParameter("checked"));
            return client.postFormBoolean("/deleteVender", params);
        } catch (Exception e) {
            LOGGER.error("deleteVender proxy error", e);
            return false;
        }
    }
}
