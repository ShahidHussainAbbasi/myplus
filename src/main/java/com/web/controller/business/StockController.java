package com.web.controller.business;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.web.util.BusinessRestClient;

/**
 * M4e.d (slice 105): the legacy local-Stock proxies (getUserStock(s)/getStock/getAllStock/addStock/deleteStock)
 * are retired — stock lives in inventory-service and the Item entity is gone. Only the purchase batch pre-fill
 * remains, and it is productId-native (inventory batches + catalog master).
 */
@RestController
public class StockController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    // The purchase batch pre-fill: on-hand/expiry/last-purchase-rate from inventory + sell rate from the catalog
    // master, keyed by the selected productId (M4e.1b picker is productId-valued).
    @RequestMapping(value = "/getStockByBatch", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getStockByBatch(@RequestParam final String batchNo,
            @RequestParam(required = false) final Long productId) {
        try {
            return client.get("/getStockByBatch",
                    "batchNo=" + batchNo + (productId != null ? "&productId=" + productId : ""));
        } catch (Exception e) {
            LOGGER.error("getStockByBatch proxy error", e);
            return null;
        }
    }
}
