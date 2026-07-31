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
 * Slice 1.2 — thin proxy to education-service's exam endpoints.
 * Design: microservices/docs/slices/edu-1.2-examinations.md
 *
 * No logic here by design: the ADMIN privilege tier, org-scoping and the exam lock all live in the
 * service, so the UI can never be the thing that enforces them.
 */
@Controller
public class ExamController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getExams", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getExams() {
        return educationClient.get("/getExams", requestUtil.getCurrentUser().getId());
    }

    /**
     * EducationRestClient.get() takes no request, so the two optional filters are appended by hand —
     * the same shape as findFc/loadFL. Both are numeric ids, so they are validated as digits rather
     * than URL-encoded: anything else is dropped instead of being forwarded downstream.
     */
    @RequestMapping(value = "/getDatesheet", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getDatesheet(HttpServletRequest request) {
        StringBuilder path = new StringBuilder("/getDatesheet");
        String sep = "?";
        for (String p : new String[] { "gradeId", "examId" }) {
            String v = request.getParameter(p);
            if (v != null && v.matches("\\d+")) {
                path.append(sep).append(p).append('=').append(v);
                sep = "&";
            }
        }
        return educationClient.get(path.toString(), requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addExam", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addExam(HttpServletRequest request) {
        return educationClient.post("/addExam", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addExamPaper", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addExamPaper(HttpServletRequest request) {
        return educationClient.post("/addExamPaper", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/setExamStatus", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> setExamStatus(HttpServletRequest request) {
        return educationClient.post("/setExamStatus", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteExam", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteExam(HttpServletRequest request) {
        return educationClient.post("/deleteExam", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteExamPaper", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteExamPaper(HttpServletRequest request) {
        return educationClient.post("/deleteExamPaper", request, requestUtil.getCurrentUser().getId());
    }
}
