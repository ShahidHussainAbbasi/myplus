package com.web.controller.education;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import com.web.util.EducationRestClient;
import com.web.util.RequestUtil;

@Controller
public class StudentController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getUserStudent", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserStudent() {
        return educationClient.get("/getUserStudent", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getUserStudents", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserStudents() {
        return educationClient.get("/getUserStudents", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getAllStudent", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getAllStudent() {
        return educationClient.get("/getAllStudent", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addStudent", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addStudent(HttpServletRequest request) {
        return educationClient.post("/addStudent", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteStudent", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteStudent(HttpServletRequest request) {
        return educationClient.post("/deleteStudent", request, requestUtil.getCurrentUser().getId());
    }

    @PostMapping("/impStudents")
    @ResponseBody
    public ResponseEntity<String> impStudents(@RequestParam("file") MultipartFile file, HttpServletRequest request) throws IOException {
        return educationClient.postMultipart("/impStudents", file, request, requestUtil.getCurrentUser().getId());
    }
}
