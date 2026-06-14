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
public class SchoolController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getMainBranchName", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getMainBranchName() {
        return educationClient.get("/getMainBranchName", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getUserSchool", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserSchool() {
        return educationClient.get("/getUserSchool", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getUserSchools", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserSchools() {
        return educationClient.get("/getUserSchools", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getAllSchool", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getAllSchool() {
        return educationClient.get("/getAllSchool", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addSchool", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addSchool(HttpServletRequest request) {
        return educationClient.post("/addSchool", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteSchool", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteSchool(HttpServletRequest request) {
        return educationClient.post("/deleteSchool", request, requestUtil.getCurrentUser().getId());
    }
}
