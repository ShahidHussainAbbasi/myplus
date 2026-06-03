package com.web.controller.business;

import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.web.util.BusinessRestClient;

@RestController
public class StockController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/getUserStock", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserItem(final HttpServletRequest request) {
        try {
            return client.get("/getUserStock");
        } catch (Exception e) {
            LOGGER.error("getUserStock proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getUserStocks", method = RequestMethod.GET)
    @ResponseBody
    public String getUserItems(final HttpServletRequest request) {
        try {
            return client.getString("/getUserStocks");
        } catch (Exception e) {
            LOGGER.error("getUserStocks proxy error", e);
            return "<option value=''> Item not available </option>";
        }
    }

    @RequestMapping(value = "/getStock", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getStock(@RequestParam final Long itemId) {
        try {
            return client.get("/getStock", "itemId=" + itemId);
        } catch (Exception e) {
            LOGGER.error("getStock proxy error", e);
            return null;
        }
    }

    @RequestMapping(value = "/getStockByBatch", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getStockByBatch(@RequestParam final String batchNo) {
        try {
            return client.get("/getStockByBatch", "batchNo=" + batchNo);
        } catch (Exception e) {
            LOGGER.error("getStockByBatch proxy error", e);
            return null;
        }
    }

    @RequestMapping(value = "/getBatchesByItem", method = RequestMethod.GET)
    @ResponseBody
    public String getBatchesByItem(@RequestParam final Long itemId, final HttpServletRequest request) {
        try {
            return client.getString("/getBatchesByItem", "itemId=" + itemId);
        } catch (Exception e) {
            LOGGER.error("getBatchesByItem proxy error", e);
            return "<option value=''> Unable to find item batch </option>";
        }
    }

    @RequestMapping(value = "/getAllStock", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getAllStock(final HttpServletRequest request) {
        try {
            return client.get("/getAllStock");
        } catch (Exception e) {
            LOGGER.error("getAllStock proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/addStock", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addStock(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/addStock", params);
        } catch (Exception e) {
            LOGGER.error("addStock proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteStock", method = RequestMethod.POST)
    @ResponseBody
    public Boolean deleteStock(HttpServletRequest req, HttpServletResponse resp) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("checked", req.getParameter("checked"));
            return client.postFormBoolean("/deleteStock", params);
        } catch (Exception e) {
            LOGGER.error("deleteStock proxy error", e);
            return false;
        }
    }
}
