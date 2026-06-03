package com.web.controller.business;

import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.web.dto.business.CustomerHistoryDTO;
import com.web.dto.business.SellDTO;
import com.web.util.BusinessRestClient;

@RestController
public class SellController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/getUserSell", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserSell(final HttpServletRequest request) {
        try {
            String q = request.getParameter("q");
            return client.get("/getUserSell", q != null ? "q=" + q : "");
        } catch (Exception e) {
            LOGGER.error("getUserSell proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/loadSR", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> loadSR(final SellDTO dto, final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/loadSR", params);
        } catch (Exception e) {
            LOGGER.error("loadSR proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getAllSell", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getAllSell(final HttpServletRequest request) {
        try {
            return client.get("/getAllSell");
        } catch (Exception e) {
            LOGGER.error("getAllSell proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/addSell", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addSell(@RequestBody final CustomerHistoryDTO dto, final HttpServletRequest request) {
        try {
            return client.postJson("/addSell", dto);
        } catch (Exception e) {
            LOGGER.error("addSell proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @PostMapping(value = "/addSelling")
    @ResponseBody
    public Map<String, Object> addSelling(@RequestBody final java.util.List<SellDTO> dtos, final HttpServletRequest request) {
        try {
            return client.postJson("/addSelling", dtos);
        } catch (Exception e) {
            LOGGER.error("addSelling proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/revertSell", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> reverSell(final SellDTO dto, final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/revertSell", params);
        } catch (Exception e) {
            LOGGER.error("revertSell proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteSell", method = RequestMethod.POST)
    @ResponseBody
    public Boolean deleteSell(HttpServletRequest req, HttpServletResponse resp) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("checked", req.getParameter("checked"));
            return client.postFormBoolean("/deleteSell", params);
        } catch (Exception e) {
            LOGGER.error("deleteSell proxy error", e);
            return false;
        }
    }

    @PostMapping(value = "/saleReturn")
    @ResponseBody
    public Map<String, Object> saleReturn(final SellDTO dto, final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/saleReturn", params);
        } catch (Exception e) {
            LOGGER.error("saleReturn proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }
}
