package com.web.controller.education;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.EducationRestClient;
import com.web.util.RequestUtil;

/**
 * Slice 2.3 — thin proxy to education-service's staff-attendance and leave endpoints.
 * Design: microservices/docs/slices/edu-2.3-staff-attendance-leave.md
 *
 * No logic here by design. The privilege split (requesting is WRITE, deciding is ADMIN), the derived
 * balances and the absence convergence all live in the service, so a caller who bypasses this proxy still
 * meets every guard.
 *
 * <p>Both screens live behind one proxy because they are one slice and share `StaffAbsence`; splitting the
 * proxy would imply a boundary that does not exist.
 */
@Controller
public class StaffLeaveController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    /** ISO dates only; anything else is dropped and the service defaults to today. */
    private static String dateParam(String value, boolean first) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return "";
        return (first ? "?" : "&") + "date=" + value;
    }

    private static String idParam(String name, String value, boolean first) {
        if (value == null || !value.matches("\\d+")) return "";
        return (first ? "?" : "&") + name + "=" + value;
    }

    /** Every parameter is optional, so make sure whichever comes first opens the query string. */
    private static String normalise(String path) {
        return path.contains("&") && !path.contains("?") ? path.replaceFirst("&", "?") : path;
    }

    // ── register ────────────────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/getStaffRegister", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getStaffRegister(HttpServletRequest request) {
        String path = "/getStaffRegister" + dateParam(request.getParameter("date"), true);
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    /** The register save is a row list, so it goes through postJson like markAttendanceBulk. */
    @RequestMapping(value = "/markStaffAttendanceBulk", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> markStaffAttendanceBulk(@RequestBody String body) {
        return educationClient.postJson("/markStaffAttendanceBulk", body);
    }

    // ── leave types ─────────────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/getLeaveTypes", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getLeaveTypes() {
        return educationClient.get("/getLeaveTypes", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/saveLeaveType", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> saveLeaveType(HttpServletRequest request) {
        return educationClient.post("/saveLeaveType", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteLeaveType", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteLeaveType(HttpServletRequest request) {
        return educationClient.post("/deleteLeaveType", request, requestUtil.getCurrentUser().getId());
    }

    // ── requests & balances ─────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/getLeaveRequests", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getLeaveRequests(HttpServletRequest request) {
        String path = normalise("/getLeaveRequests"
                + idParam("staffId", request.getParameter("staffId"), true)
                + idParam("year", request.getParameter("year"), false));
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getLeaveBalances", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getLeaveBalances(HttpServletRequest request) {
        String path = normalise("/getLeaveBalances"
                + idParam("staffId", request.getParameter("staffId"), true)
                + idParam("year", request.getParameter("year"), false));
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/saveLeaveRequest", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> saveLeaveRequest(HttpServletRequest request) {
        return educationClient.post("/saveLeaveRequest", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/decideLeaveRequest", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> decideLeaveRequest(HttpServletRequest request) {
        return educationClient.post("/decideLeaveRequest", request, requestUtil.getCurrentUser().getId());
    }
}
