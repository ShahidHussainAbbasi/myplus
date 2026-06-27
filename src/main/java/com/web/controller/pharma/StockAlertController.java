package com.web.controller.pharma;

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

import com.web.util.InventoryRestClient;

/**
 * Surfaces inventory-service stock alerts (near-expiry / low-stock — slice 45) to the dashboard. Reuses the
 * existing inventory StockAlert system; generic across verticals (pharmacy near-expiry uses it).
 */
@Controller
public class StockAlertController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private InventoryRestClient inventory;

    @RequestMapping(value = "/getStockAlerts", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getStockAlerts(final HttpServletRequest request) {
        try { return inventory.get("/alerts"); }
        catch (Exception e) { LOGGER.error("getStockAlerts proxy error", e); return Collections.singletonMap("success", false); }
    }
}
