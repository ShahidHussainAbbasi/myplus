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

@Controller
public class GradeController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getUserGrade", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserGrade() {
        return educationClient.get("/getUserGrade", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getUserGrades", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserGrades() {
        return educationClient.get("/getUserGrades", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getAllGrade", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getAllGrade() {
        return educationClient.get("/getAllGrade", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addGrade", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addGrade(HttpServletRequest request) {
        return educationClient.post("/addGrade", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteGrade", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteGrade(HttpServletRequest request) {
        return educationClient.post("/deleteGrade", request, requestUtil.getCurrentUser().getId());
    }
}
