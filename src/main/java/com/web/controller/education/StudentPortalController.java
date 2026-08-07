package com.web.controller.education;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.EducationRestClient;
import com.web.util.RequestUtil;

/**
 * Slice 3.3 — thin proxy for the STUDENT-facing portal reads.
 * Design: microservices/docs/slices/edu-3.3-student-portal.md
 *
 * <p><b>Note what is absent: there is no {@code childPath} helper here, and no request parameter is read at
 * all.</b> Its guardian twin has one, because a guardian chooses between children. A student's set has
 * exactly one member (D2), so this proxy forwards a bare path — which means an {@code ?enrollNo=} appended
 * by a curious caller is not merely rejected downstream, it is never forwarded in the first place.
 *
 * <p>Read-only and deliberately tiny: exactly five endpoints, mirroring education-service's own surface, so
 * the monolith cannot become a side door for a student session. No logic lives here — the resolver, the
 * access check and both feature switches are in education-service, so bypassing this proxy meets the same
 * gate.
 *
 * <p>Kept separate from {@link PortalAccessController} (staff: invite/revoke) for the same reason 3.1 kept
 * its own proxies apart: a portal session must never be one routing mistake away from an endpoint that
 * grants access.
 */
@Controller
public class StudentPortalController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    private Long caller() {
        return requestUtil.getCurrentUser().getId();
    }

    @RequestMapping(value = "/portal/my/me", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> me() {
        return educationClient.get("/portal/my/me", caller());
    }

    @RequestMapping(value = "/portal/my/timetable", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> timetable() {
        return educationClient.get("/portal/my/timetable", caller());
    }

    @RequestMapping(value = "/portal/my/results", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> results() {
        return educationClient.get("/portal/my/results", caller());
    }

    @RequestMapping(value = "/portal/my/homework", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> homework() {
        return educationClient.get("/portal/my/homework", caller());
    }

    @RequestMapping(value = "/portal/my/attendance", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> attendance() {
        return educationClient.get("/portal/my/attendance", caller());
    }

    /** Slice 3.5 — school notices addressed to me or to everyone. */
    @RequestMapping(value = "/portal/my/notices", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> notices() {
        return educationClient.get("/portal/my/notices", caller());
    }
}
