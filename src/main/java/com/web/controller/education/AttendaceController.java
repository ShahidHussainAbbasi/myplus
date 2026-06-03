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
public class AttendaceController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getUserStudentMap", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserStudentMap() {
        return educationClient.get("/getUserStudentMap", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getUserA", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserA() {
        return educationClient.get("/getUserA", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getAllA", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getAllA() {
        return educationClient.get("/getAllA", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/markAttendance2", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> markAttendance2(HttpServletRequest request) {
        return educationClient.post("/markAttendance2", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/markAttendance", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> markAttendance(HttpServletRequest request) {
        return educationClient.post("/markAttendance", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteA", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteA(HttpServletRequest request) {
        return educationClient.post("/deleteA", request, requestUtil.getCurrentUser().getId());
    }
}
