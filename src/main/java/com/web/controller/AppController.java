package com.web.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.web.util.RequestUtil;

@Controller
public class AppController {

    @Autowired
    private RequestUtil requestUtil;

    @GetMapping("/")
    public String landing() {
        return "maxtheservice_dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        try {
            String userType = requestUtil.getCurrentUser().getUserType();
            if (userType == null) return "redirect:/";
            switch (userType.toUpperCase()) {
                case "BUSINESS":    return "redirect:/businessDashboard";
                case "PHARMA":      return "redirect:/pharmaDashboard"; // pharmacy vertical — reuses the trade dashboard
                case "EDUCATION":   return "redirect:/educationDashboard";
                case "WELFARE":     return "redirect:/welfareDashboard";
                case "AGRICULTURE": return "redirect:/agricultureDashboard";
                default:            return "redirect:/";
            }
        } catch (Exception e) {
            return "redirect:/";
        }
    }
}
