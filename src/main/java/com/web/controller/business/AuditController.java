package com.web.controller.business;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.AuditRestClient;

/** Audit #6: proxy the tenant's append-only audit trail from the standalone audit-service to the dashboard. */
@Controller
public class AuditController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private AuditRestClient client;

    /** GET the audit trail (raw JSON array), optional ?action= &limit= forwarded to audit-service. */
    @RequestMapping(value = "/getAuditLog", method = RequestMethod.GET)
    @ResponseBody
    public String getAuditLog(final HttpServletRequest request) {
        try {
            StringBuilder q = new StringBuilder();
            String action = request.getParameter("action");
            String limit = request.getParameter("limit");
            if (action != null && !action.isEmpty()) q.append("action=").append(action);
            if (limit != null && !limit.isEmpty()) q.append(q.length() > 0 ? "&" : "").append("limit=").append(limit);
            return client.get("", q.toString());
        } catch (Exception e) {
            LOGGER.error("getAuditLog proxy error", e);
            return "{\"status\":\"ERROR\"}";
        }
    }
}
