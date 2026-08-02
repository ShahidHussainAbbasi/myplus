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
 * Slice 2.1 — thin proxy to education-service's timetable endpoints.
 * Design: microservices/docs/slices/edu-2.1-timetable.md
 *
 * No logic here by design. Clash detection, the ADMIN tier and the copy-into-a-non-empty-term refusal all
 * live in the service, so a caller who skips this proxy still meets every guard.
 */
@Controller
public class TimetableController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    /** Ids are digit-validated rather than URL-encoded — junk is dropped, not forwarded (1.2's shape). */
    private static String idParam(String name, String value, boolean first) {
        if (value == null || !value.matches("\\d+")) return "";
        return (first ? "?" : "&") + name + "=" + value;
    }

    @RequestMapping(value = "/getPeriods", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getPeriods() {
        return educationClient.get("/getPeriods", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/savePeriod", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> savePeriod(HttpServletRequest request) {
        return educationClient.post("/savePeriod", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deletePeriod", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deletePeriod(HttpServletRequest request) {
        return educationClient.post("/deletePeriod", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getTimetable", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getTimetable(HttpServletRequest request) {
        String path = "/getTimetable"
                + idParam("termId", request.getParameter("termId"), true)
                + idParam("gradeId", request.getParameter("gradeId"), false)
                + idParam("staffId", request.getParameter("staffId"), false);
        // Every param may be absent (the whole-term grid), so ensure the first one starts the query string.
        if (path.contains("&") && !path.contains("?")) path = path.replaceFirst("&", "?");
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/saveTimetableEntry", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> saveTimetableEntry(HttpServletRequest request) {
        return educationClient.post("/saveTimetableEntry", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteTimetableEntry", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteTimetableEntry(HttpServletRequest request) {
        return educationClient.post("/deleteTimetableEntry", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/copyTimetable", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> copyTimetable(HttpServletRequest request) {
        return educationClient.post("/copyTimetable", request, requestUtil.getCurrentUser().getId());
    }
}
