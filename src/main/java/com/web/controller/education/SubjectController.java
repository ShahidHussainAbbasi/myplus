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
public class SubjectController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getUserSubject", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserSubject() {
        return educationClient.get("/getUserSubject", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getUserSubjects", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserSubjects() {
        return educationClient.get("/getUserSubjects", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getAllSubject", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getAllSubject() {
        return educationClient.get("/getAllSubject", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addSubject", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addSubject(HttpServletRequest request) {
        return educationClient.post("/addSubject", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteSubject", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteSubject(HttpServletRequest request) {
        return educationClient.post("/deleteSubject", request, requestUtil.getCurrentUser().getId());
    }
}
