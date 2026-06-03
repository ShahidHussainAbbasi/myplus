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
public class DiscountController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getUserDiscount", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserDiscount() {
        return educationClient.get("/getUserDiscount", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getUserDiscounts", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getUserDiscounts() {
        return educationClient.get("/getUserDiscounts", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/getAllDiscount", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getAllDiscount() {
        return educationClient.get("/getAllDiscount", requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/addDiscount", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> addDiscount(HttpServletRequest request) {
        return educationClient.post("/addDiscount", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/deleteDiscount", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteDiscount(HttpServletRequest request) {
        return educationClient.post("/deleteDiscount", request, requestUtil.getCurrentUser().getId());
    }
}
