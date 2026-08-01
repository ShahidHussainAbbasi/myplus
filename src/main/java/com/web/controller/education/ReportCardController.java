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
 * Slice 1.5 — thin proxy to education-service's report-card endpoints.
 * Design: microservices/docs/slices/edu-1.5-report-cards.md
 *
 * No logic here by design. The ADMIN tier on publishing, the weights-total-100 refusal and the snapshot
 * rule all live in the service, so the UI can never be the thing that enforces them — and a caller who
 * skips this proxy entirely still meets every guard.
 */
@Controller
public class ReportCardController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    /** Enrolment numbers are free text, so they are URL-encoded; ids are digit-validated (1.2's shape). */
    private static String enrollParam(String enrollNo) {
        return URLEncoder.encode(enrollNo == null ? "" : enrollNo, StandardCharsets.UTF_8);
    }

    private static String idParam(String name, String value) {
        return value != null && value.matches("\\d+") ? "&" + name + "=" + value : "";
    }

    @RequestMapping(value = "/getReportCardPreview", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getReportCardPreview(HttpServletRequest request) {
        String path = "/getReportCardPreview?enrollNo=" + enrollParam(request.getParameter("enrollNo"))
                + idParam("termId", request.getParameter("termId"));
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getReportCard", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getReportCard(HttpServletRequest request) {
        String path = "/getReportCard?enrollNo=" + enrollParam(request.getParameter("enrollNo"))
                + idParam("termId", request.getParameter("termId"))
                + idParam("id", request.getParameter("id"));
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getTranscript", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getTranscript(HttpServletRequest request) {
        String superseded = request.getParameter("includeSuperseded");
        String path = "/getTranscript?enrollNo=" + enrollParam(request.getParameter("enrollNo"))
                + ("true".equalsIgnoreCase(superseded) ? "&includeSuperseded=true" : "");
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/publishReportCard", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> publishReportCard(HttpServletRequest request) {
        return educationClient.post("/publishReportCard", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/withdrawReportCard", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> withdrawReportCard(HttpServletRequest request) {
        return educationClient.post("/withdrawReportCard", request, requestUtil.getCurrentUser().getId());
    }
}
