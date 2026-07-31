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
 * Slice 1.1 — thin proxy to education-service's academic year / term endpoints.
 * Design: microservices/docs/slices/edu-1.1-academic-year-term.md
 *
 * No logic here by design: privilege gating (ADMIN_PRIVILEGE for structure writes) and org-scoping
 * both live in the service, so the UI cannot be the thing that enforces them.
 */
@Controller
public class AcademicYearController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getAcademicYears", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getAcademicYears() {
        return educationClient.get("/getAcademicYears", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getCurrentTerm", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getCurrentTerm() {
        return educationClient.get("/getCurrentTerm", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addAcademicYear", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addAcademicYear(HttpServletRequest request) {
        return educationClient.post("/addAcademicYear", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addTerm", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addTerm(HttpServletRequest request) {
        return educationClient.post("/addTerm", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/pinCurrentTerm", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> pinCurrentTerm(HttpServletRequest request) {
        return educationClient.post("/pinCurrentTerm", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteTerm", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteTerm(HttpServletRequest request) {
        return educationClient.post("/deleteTerm", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteAcademicYear", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteAcademicYear(HttpServletRequest request) {
        return educationClient.post("/deleteAcademicYear", request, requestUtil.getCurrentUser().getId());
    }
}
