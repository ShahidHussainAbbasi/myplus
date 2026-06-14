package com.web.controller;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import com.web.util.AppUtil;
import com.web.util.AppointmentRestClient;

/**
 * Appointment module dashboard (its own dashboard, like education/business — users with
 * {@code userType=APPOINTMENT} land here via the success handler). Renders the shell + the static
 * country list for the hospital form; hospitals/doctors/appointments load via AJAX through the proxy.
 * No local DB and no direct {@code User} read.
 */
@Controller
public class AppointmentDashboardController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private AppointmentRestClient appointment;

    @Autowired
    private AppUtil appUtil;

    @GetMapping("/appointmentDashboard")
    public ModelAndView appointmentDashboard(final HttpServletRequest request) {
        ModelAndView mav = new ModelAndView("appointmentDashboard");
        mav.addObject("countries", appUtil.countryMap);
        return mav;
    }

    /** Org-scoped appointments (enriched with patient/doctor/hospital names) as JSON for the table. */
    @SuppressWarnings("unchecked")
    @GetMapping("/loadAppointments")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> loadAppointments(final HttpServletRequest request) {
        try {
            Map<String, Object> resp = appointment.getMap("/appointments");
            List<Map<String, Object>> appointments = (List<Map<String, Object>>) resp.get("data");
            if (appointments == null || appointments.isEmpty()) {
                return new ResponseEntity<>(HttpStatus.NO_CONTENT);
            }
            return new ResponseEntity<>(appointments, HttpStatus.OK);
        } catch (Exception e) {
            LOGGER.error("loadAppointments failed", e);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        }
    }
}
