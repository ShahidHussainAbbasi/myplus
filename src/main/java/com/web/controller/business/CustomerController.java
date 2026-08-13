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
public class CustomerController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/getUserCustomer", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserCustomer(final HttpServletRequest request) {
        try {
            return client.get("/getUserCustomer");
        } catch (Exception e) {
            LOGGER.error("getUserCustomer proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getUserCustomers", method = RequestMethod.GET)
    @ResponseBody
    public String getUserCustomers(final HttpServletRequest request) {
        try {
            return client.getString("/getUserCustomers");
        } catch (Exception e) {
            LOGGER.error("getUserCustomers proxy error", e);
            return "<option value=''>No Data found</option>";
        }
    }

    @RequestMapping(value = "/getAllCustomer", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getAllCustomer(final HttpServletRequest request) {
        try {
            return client.get("/getAllCustomer");
        } catch (Exception e) {
            LOGGER.error("getAllCustomer proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/addCustomer", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addCustomer(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/addCustomer", params);
        } catch (Exception e) {
            LOGGER.error("addCustomer proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /** SF-5 Model B: the customer's store-credit balance → business-service /customerCredit (checkout "apply credit"). */
    @RequestMapping(value = "/customerCredit", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> customerCredit(final HttpServletRequest request) {
        try {
            String id = request.getParameter("customerId");
            return client.get("/customerCredit", "customerId=" + (id == null ? "" : id));
        } catch (Exception e) {
            LOGGER.error("customerCredit proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /**
     * OMS O7 D2d — the outlets a field rep may book for → business-service /outlets.
     *
     * <p>Territory-aware and identity-only. NOT {@code getUserCustomer}: that read is scoped by the audit field
     * {@code userId}, so a rep — who creates no outlets, because the company does — got an empty picker.
     */
    @RequestMapping(value = "/outlets", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> outlets() {
        try {
            return client.get("/outlets", "");
        } catch (Exception e) {
            LOGGER.error("outlets proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /**
     * OMS O7 D2 — the outlet's credit STANDING (limit, owed, available) → business-service /creditStanding.
     *
     * <p>Not to be confused with {@code /customerCredit} above: that is store credit the shop is holding FOR
     * the customer, this is what the customer owes the SHOP. The booker is shown this before writing an order,
     * so an over-limit outlet is caught at the counter instead of by the warehouse a day later.
     */
    @RequestMapping(value = "/creditStanding", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> creditStanding(final HttpServletRequest request) {
        try {
            String id = request.getParameter("customerId");
            return client.get("/creditStanding", "customerId=" + (id == null ? "" : id));
        } catch (Exception e) {
            LOGGER.error("creditStanding proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /** Receive Payment (AR subledger) → business-service /receivePayment: allocates the receipt to the customer's
     *  open invoices, recomputes their due, and records it in the shared finance ledger. */
    @RequestMapping(value = "/receivePayment", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> receivePayment(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/receivePayment", params);
        } catch (Exception e) {
            LOGGER.error("receivePayment proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    // ── B2B Phase 4a — account hierarchy ────────────────────────────────────────────────────────────────────

    /** Put a customer under a parent account (or detach it) → business-service, which also re-stamps the credit
     *  account across the affected subtree. Guard rejections come back as FAILED + the operator-facing reason. */
    @RequestMapping(value = "/setCustomerAccountParent", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> setCustomerAccountParent(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/setCustomerAccountParent", params);
        } catch (Exception e) {
            LOGGER.error("setCustomerAccountParent proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /** The credit group a customer draws on: head, members, limit and pooled due. */
    @RequestMapping(value = "/customerAccountGroup", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> customerAccountGroup(final HttpServletRequest request) {
        try {
            String id = request.getParameter("customerId");
            return client.get("/customerAccountGroup", "customerId=" + (id == null ? "" : id));
        } catch (Exception e) {
            LOGGER.error("customerAccountGroup proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /** Trade customers with no party link — they cannot join a group and must be visible, not silently omitted. */
    @RequestMapping(value = "/unbridgedCustomers", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> unbridgedCustomers() {
        try {
            return client.get("/unbridgedCustomers");
        } catch (Exception e) {
            LOGGER.error("unbridgedCustomers proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteCustomer", method = RequestMethod.POST)
    @ResponseBody
    public Boolean deleteCustomer(HttpServletRequest req, HttpServletResponse resp) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("checked", req.getParameter("checked"));
            return client.postFormBoolean("/deleteCustomer", params);
        } catch (Exception e) {
            LOGGER.error("deleteCustomer proxy error", e);
            return false;
        }
    }
}
