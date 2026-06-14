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
public class VehicleController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getUserVehicle", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserVehicle() {
        return educationClient.get("/getUserVehicle", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getUserVehicles", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserVehicles() {
        return educationClient.get("/getUserVehicles", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getAllVehicle", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getAllVehicle() {
        return educationClient.get("/getAllVehicle", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addVehicle", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addVehicle(HttpServletRequest request) {
        return educationClient.post("/addVehicle", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteVehicle", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteVehicle(HttpServletRequest request) {
        return educationClient.post("/deleteVehicle", request, requestUtil.getCurrentUser().getId());
    }
}
