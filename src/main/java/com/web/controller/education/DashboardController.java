package com.web.controller.education;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.EducationRestClient;
import com.web.util.RequestUtil;

@Controller
public class DashboardController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getDashboardData", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getDashboardData() {
        return educationClient.get("/getDashboardData", requestUtil.getCurrentUser().getId());
    }

    /** Rich, org-scoped analytics (KPIs + chart series) for the owner dashboard (slice 22). */
    @RequestMapping(value = "/getDashboardAnalytics", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getDashboardAnalytics() {
        return educationClient.get("/getDashboardAnalytics", requestUtil.getCurrentUser().getId());
    }
}
