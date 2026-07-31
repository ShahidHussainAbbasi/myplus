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
 * Slice 1.3 — thin proxy to education-service's marks endpoints.
 * Design: microservices/docs/slices/edu-1.3-marks-entry.md
 *
 * No logic here by design: the WRITE tier, branch scoping, per-row validation, the first-mark lock and
 * the audit trail all live in the service, so the UI can never be the thing that enforces them.
 */
@Controller
public class MarkController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    /** Query params are appended by hand — EducationRestClient.get() takes no request (the findFc shape). */
    @RequestMapping(value = "/getMarksSheet", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getMarksSheet(HttpServletRequest request) {
        String id = request.getParameter("examPaperId");
        String path = "/getMarksSheet" + (id != null && id.matches("\\d+") ? "?examPaperId=" + id : "");
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getStudentMarks", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getStudentMarks(HttpServletRequest request) {
        String en = request.getParameter("enrollNo");
        // URL-encoded rather than digit-validated: an enrolment number is free text, not an id.
        String path = "/getStudentMarks?enrollNo="
                + java.net.URLEncoder.encode(en == null ? "" : en, java.nio.charset.StandardCharsets.UTF_8);
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    /** The grid save is JSON (a nested row list), so it goes through postJson like markAttendanceBulk. */
    @RequestMapping(value = "/saveMarksBulk", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> saveMarksBulk(@RequestBody String body) {
        return educationClient.postJson("/saveMarksBulk", body);
    }
}
