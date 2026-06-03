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
public class StaffController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getUserStaff", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserStaff() {
        return educationClient.get("/getUserStaff", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getUserStaffs", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserStaffs() {
        return educationClient.get("/getUserStaffs", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getAllStaff", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getAllStaff() {
        return educationClient.get("/getAllStaff", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addStaff", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addStaff(HttpServletRequest request) {
        return educationClient.post("/addStaff", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteStaff", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteStaff(HttpServletRequest request) {
        return educationClient.post("/deleteStaff", request, requestUtil.getCurrentUser().getId());
    }
}
