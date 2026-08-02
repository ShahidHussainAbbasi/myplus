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
 * Slice 2.2 — thin proxy to education-service's substitution endpoints.
 * Design: microservices/docs/slices/edu-2.2-substitution.md
 *
 * No logic here by design. The clash reuse, the ADMIN tier and the free-teacher exclusions all live in the
 * service, so a caller who skips this proxy still meets every guard.
 */
@Controller
public class SubstitutionController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    /** ISO dates only — anything else is dropped, and the service then defaults to today. */
    private static String dateParam(String value, boolean first) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return "";
        return (first ? "?" : "&") + "date=" + value;
    }

    private static String idParam(String name, String value, boolean first) {
        if (value == null || !value.matches("\\d+")) return "";
        return (first ? "?" : "&") + name + "=" + value;
    }

    @RequestMapping(value = "/getSubstitutionDay", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getSubstitutionDay(HttpServletRequest request) {
        String path = "/getSubstitutionDay"
                + dateParam(request.getParameter("date"), true)
                + idParam("termId", request.getParameter("termId"), false);
        // Every parameter is optional (the screen defaults to today), so make sure the first one present
        // actually starts the query string.
        if (path.contains("&") && !path.contains("?")) path = path.replaceFirst("&", "?");
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/markStaffAbsent", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> markStaffAbsent(HttpServletRequest request) {
        return educationClient.post("/markStaffAbsent", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/clearStaffAbsence", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> clearStaffAbsence(HttpServletRequest request) {
        return educationClient.post("/clearStaffAbsence", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/assignSubstitute", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> assignSubstitute(HttpServletRequest request) {
        return educationClient.post("/assignSubstitute", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/clearSubstitute", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> clearSubstitute(HttpServletRequest request) {
        return educationClient.post("/clearSubstitute", request, requestUtil.getCurrentUser().getId());
    }
}
