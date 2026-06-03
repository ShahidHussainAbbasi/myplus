package com.web.controller.education;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.EducationRestClient;
import com.web.util.RequestUtil;

@Controller
public class FeeCollectionController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getUserFc", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserFc() {
        return educationClient.get("/getUserFc", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/findFc", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> findFc(HttpServletRequest request) {
        return educationClient.get("/findFc?input=" + request.getParameter("input"), requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/loadFV", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> loadFV(HttpServletRequest request) {
        return educationClient.post("/loadFV", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/loadFL", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> loadFL(HttpServletRequest request) {
        return educationClient.post("/loadFL", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/loadFR", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> loadFR(HttpServletRequest request) {
        return educationClient.post("/loadFR", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getAllFc", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getAllFc() {
        return educationClient.get("/getAllFc", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addFc", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addFc(HttpServletRequest request) {
        return educationClient.post("/addFc", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteFc", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteFc(HttpServletRequest request) {
        return educationClient.post("/deleteFc", request, requestUtil.getCurrentUser().getId());
    }

    @GetMapping("favicon.ico")
    @ResponseBody
    public String returnNoFavicon() {
        return "";
    }
}
