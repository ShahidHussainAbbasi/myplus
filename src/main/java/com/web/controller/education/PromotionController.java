package com.web.controller.education;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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
 * Slice 1.6 — thin proxy to education-service's promotion endpoints.
 * Design: microservices/docs/slices/edu-1.6-promotion.md
 *
 * No logic here by design. The ADMIN tier, the one-decision-per-student-per-year constraint and the
 * plan/apply split all live in the service, so a caller who bypasses this proxy meets every guard.
 */
@Controller
public class PromotionController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    private static String idParam(String name, String value, boolean first) {
        if (value == null || !value.matches("\\d+")) return "";
        return (first ? "?" : "&") + name + "=" + value;
    }

    @RequestMapping(value = "/getPromotionPlan", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getPromotionPlan(HttpServletRequest request) {
        String path = "/getPromotionPlan"
                + idParam("academicYearId", request.getParameter("academicYearId"), true)
                + idParam("fromGradeId", request.getParameter("fromGradeId"), false)
                + idParam("toGradeId", request.getParameter("toGradeId"), false);
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getPromotionHistory", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getPromotionHistory(HttpServletRequest request) {
        // An enrolment number is free text, so it is URL-encoded rather than digit-validated.
        String en = request.getParameter("enrollNo");
        String path = "/getPromotionHistory?enrollNo="
                + URLEncoder.encode(en == null ? "" : en, StandardCharsets.UTF_8)
                + idParam("academicYearId", request.getParameter("academicYearId"), false);
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    /** The run body is a row list, so it goes through postJson like saveMarksBulk. */
    @RequestMapping(value = "/runPromotion", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> runPromotion(@RequestBody String body) {
        return educationClient.postJson("/runPromotion", body);
    }

    @RequestMapping(value = "/undoPromotion", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> undoPromotion(HttpServletRequest request) {
        return educationClient.post("/undoPromotion", request, requestUtil.getCurrentUser().getId());
    }
}
