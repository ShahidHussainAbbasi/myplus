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
public class AlertsController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getUserAlerts", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserAlerts() {
        return educationClient.get("/getUserAlerts", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getUserAlertss", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserAlertss() {
        return educationClient.get("/getUserAlertss", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getAllAlerts", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getAllAlerts() {
        return educationClient.get("/getAllAlerts", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addAlerts", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addAlerts(HttpServletRequest request) {
        return educationClient.post("/addAlerts", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteAlerts", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteAlerts(HttpServletRequest request) {
        return educationClient.post("/deleteAlerts", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/sendAlerts", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> sendAlerts(HttpServletRequest request) {
        return educationClient.post("/sendAlerts", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/importCSV", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> importCSV(@RequestParam("file") MultipartFile file, HttpServletRequest request) throws IOException {
        return educationClient.postMultipart("/importCSV", file, request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getUserPA", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserPA() {
        return educationClient.get("/getUserPA", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/sendPA", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> sendPA(HttpServletRequest request) {
        return educationClient.post("/sendPA", request, requestUtil.getCurrentUser().getId());
    }
}
