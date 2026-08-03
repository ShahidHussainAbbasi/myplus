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
 * Slice 2.4 — thin proxy to education-service's homework endpoints.
 * Design: microservices/docs/slices/edu-2.4-homework.md
 *
 * No logic here by design. The WRITE tier, the lazy-row rule and the delete-when-graded refusal all live in
 * the service, so a caller who bypasses this proxy still meets every guard.
 */
@Controller
public class HomeworkController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    /** Ids are digit-validated rather than URL-encoded — junk is dropped, not forwarded (1.2's shape). */
    private static String idParam(String name, String value, boolean first) {
        if (value == null || !value.matches("\\d+")) return "";
        return (first ? "?" : "&") + name + "=" + value;
    }

    @RequestMapping(value = "/getHomework", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getHomework(HttpServletRequest request) {
        String path = "/getHomework" + idParam("subjectId", request.getParameter("subjectId"), true);
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/saveHomework", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> saveHomework(HttpServletRequest request) {
        return educationClient.post("/saveHomework", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteHomework", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteHomework(HttpServletRequest request) {
        return educationClient.post("/deleteHomework", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getHomeworkSheet", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getHomeworkSheet(HttpServletRequest request) {
        String path = "/getHomeworkSheet" + idParam("homeworkId", request.getParameter("homeworkId"), true);
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    /** The sheet save is a row list, so it goes through postJson like saveMarksBulk. */
    @RequestMapping(value = "/saveSubmissionBulk", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> saveSubmissionBulk(@RequestBody String body) {
        return educationClient.postJson("/saveSubmissionBulk", body);
    }
}
