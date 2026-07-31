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
 * Slice 1.4 — thin proxy to education-service's grading endpoints.
 * Design: microservices/docs/slices/edu-1.4-grading-scales.md
 *
 * No logic here by design: the ADMIN tier, org-scoping and the whole-scale validation all live in the
 * service, so the UI can never be the thing that enforces them.
 */
@Controller
public class GradingController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getGradingScale", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getGradingScale() {
        return educationClient.get("/getGradingScale", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/saveGradeBand", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> saveGradeBand(HttpServletRequest request) {
        return educationClient.post("/saveGradeBand", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteGradeBand", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteGradeBand(HttpServletRequest request) {
        return educationClient.post("/deleteGradeBand", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/applyGradingPreset", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> applyGradingPreset(HttpServletRequest request) {
        return educationClient.post("/applyGradingPreset", request, requestUtil.getCurrentUser().getId());
    }
}
