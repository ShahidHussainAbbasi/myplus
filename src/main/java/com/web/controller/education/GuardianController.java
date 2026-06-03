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
public class GuardianController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getUserGuardian", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserGuardian() {
        return educationClient.get("/getUserGuardian", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getUserGuardians", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserGuardians() {
        return educationClient.get("/getUserGuardians", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getAllGuardian", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getAllGuardian() {
        return educationClient.get("/getAllGuardian", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addGuardian", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addGuardian(HttpServletRequest request) {
        return educationClient.post("/addGuardian", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteGuardian", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteGuardian(HttpServletRequest request) {
        return educationClient.post("/deleteGuardian", request, requestUtil.getCurrentUser().getId());
    }

    @PostMapping("/impG")
    @ResponseBody
    public ResponseEntity<String> impG(@RequestParam("file") MultipartFile file, HttpServletRequest request) throws IOException {
        return educationClient.postMultipart("/impG", file, request, requestUtil.getCurrentUser().getId());
    }
}
