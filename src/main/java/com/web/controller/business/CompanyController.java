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
public class CompanyController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/getUserCompany", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserCompany(final HttpServletRequest request) {
        try {
            return client.get("/getUserCompany");
        } catch (Exception e) {
            LOGGER.error("getUserCompany proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getUserCompanies", method = RequestMethod.GET)
    @ResponseBody
    public String getUserCompanies(final HttpServletRequest request) {
        try {
            return client.getString("/getUserCompanies");
        } catch (Exception e) {
            LOGGER.error("getUserCompanies proxy error", e);
            return "<option value=''>No Data found</option>";
        }
    }

    @RequestMapping(value = "/getAllCompany", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getAllCompany(final HttpServletRequest request) {
        try {
            return client.get("/getAllCompany");
        } catch (Exception e) {
            LOGGER.error("getAllCompany proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/addCompany", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addCompany(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/addCompany", params);
        } catch (Exception e) {
            LOGGER.error("addCompany proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteCompany", method = RequestMethod.POST)
    @ResponseBody
    public Boolean deleteCompany(HttpServletRequest req, HttpServletResponse resp) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("checked", req.getParameter("checked"));
            return client.postFormBoolean("/deleteCompany", params);
        } catch (Exception e) {
            LOGGER.error("deleteCompany proxy error", e);
            return false;
        }
    }
}
