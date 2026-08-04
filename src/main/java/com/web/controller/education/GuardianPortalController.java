package com.web.controller.education;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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
 * Slice 3.1 — thin proxy for the GUARDIAN-facing portal reads.
 * Design: microservices/docs/slices/edu-3.1-guardian-portal.md
 *
 * <p><b>Read-only, and deliberately tiny.</b> This proxy exposes exactly the six portal endpoints and
 * nothing else — the allowlist of D2 is mirrored here so the monolith cannot become a side door into a
 * staff endpoint for a guardian session.
 *
 * <p>No logic: the guardian lookup, the child-set derivation and the intersection all live in
 * education-service, so a caller who bypasses this proxy meets exactly the same gate.
 *
 * <p>Kept separate from {@link PortalAccessController} (staff: invite/revoke) on purpose — a guardian session
 * must never be one routing mistake away from an endpoint that grants access.
 */
@Controller
public class GuardianPortalController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    /** Enrolment numbers are free text, so they are URL-encoded rather than digit-validated. */
    private String childPath(String endpoint, HttpServletRequest request) {
        String en = request.getParameter("enrollNo");
        return endpoint + "?enrollNo=" + URLEncoder.encode(en == null ? "" : en, StandardCharsets.UTF_8);
    }

    @RequestMapping(value = "/portal/me", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> me() {
        return educationClient.get("/portal/me", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/portal/children", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> children() {
        return educationClient.get("/portal/children", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/portal/results", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> results(HttpServletRequest request) {
        return educationClient.get(childPath("/portal/results", request),
                requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/portal/attendance", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> attendance(HttpServletRequest request) {
        return educationClient.get(childPath("/portal/attendance", request),
                requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/portal/dues", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> dues(HttpServletRequest request) {
        return educationClient.get(childPath("/portal/dues", request),
                requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/portal/homework", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> homework(HttpServletRequest request) {
        return educationClient.get(childPath("/portal/homework", request),
                requestUtil.getCurrentUser().getId());
    }
}
