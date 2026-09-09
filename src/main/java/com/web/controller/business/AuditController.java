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
        } catch (org.springframework.web.client.HttpClientErrorException.Forbidden denied) {
            /*
             * ⚠ A REFUSAL IS NOT AN OUTAGE, and saying so costs one branch.
             *
             * Every failure used to collapse to {"status":"ERROR"}, so the screen told the shopkeeper to
             * "check that audit-service is running" when the service was running perfectly and had simply
             * refused them. Chasing that took several steps and a container log to unpick.
             *
             * audit-service guards the trail with ROLE_OWNER / ROLE_ADMIN deliberately — a privilege gate
             * would be no gate at all, since a tenant owner holds the super privilege set inside their own
             * organization. So a 403 here is the control working, and it is reported as what it is.
             *
             * Deliberately says nothing about WHO is allowed beyond the role, and nothing about the
             * upstream URL: a refusal message is read by whoever was refused.
             */
            LOGGER.warn("getAuditLog refused for the current user (403 from audit-service)");
            return "{\"status\":\"ERROR\",\"message\":\"Only an owner or a platform operator "
                 + "can read the audit trail.\"}";
        } catch (Exception e) {
            LOGGER.error("getAuditLog proxy error", e);
            return "{\"status\":\"ERROR\",\"message\":\"Could not reach the audit service.\"}";
        }
    }
}
