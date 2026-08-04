package com.web.controller.education;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.EducationRestClient;
import com.web.util.RequestUtil;

/**
 * Slice 3.1 — thin proxy for the SCHOOL-facing side of the portal: invite and revoke.
 * Design: microservices/docs/slices/edu-3.1-guardian-portal.md
 *
 * Deliberately a different class from the guardian-facing proxy. Granting an outsider sight of a child's
 * record is `ADMIN_PRIVILEGE` and is enforced in the service; keeping the two proxies apart means a guardian
 * session is never one routing mistake away from an endpoint that changes access.
 */
@Controller
public class PortalAccessController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getPortalAccess", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getPortalAccess() {
        return educationClient.get("/getPortalAccess", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/invitePortalAccess", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> invitePortalAccess(HttpServletRequest request) {
        return educationClient.post("/invitePortalAccess", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/revokePortalAccess", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> revokePortalAccess(HttpServletRequest request) {
        return educationClient.post("/revokePortalAccess", request, requestUtil.getCurrentUser().getId());
    }
}
