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
public class OwnerController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getUserOwner", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserOwner() {
        return educationClient.get("/getUserOwner", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getUserOwners", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserOwners() {
        return educationClient.get("/getUserOwners", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getAllOwner", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getAllOwner() {
        return educationClient.get("/getAllOwner", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addOwner", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addOwner(HttpServletRequest request) {
        return educationClient.post("/addOwner", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteOwner", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteOwner(HttpServletRequest request) {
        return educationClient.post("/deleteOwner", request, requestUtil.getCurrentUser().getId());
    }
}
