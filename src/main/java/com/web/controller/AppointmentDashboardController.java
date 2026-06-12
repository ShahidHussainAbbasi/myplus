package com.web.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import com.web.pagination.model.PagerModel;
import com.web.util.AppointmentRestClient;

/**
 * Appointment dashboard. Proxies to appointment-service (org-scoped by the JWT) — no local DB and no
 * direct {@code User} read (that coupling is gone, which clears the way for the auth-residual cleanup).
 */
@Controller
public class AppointmentDashboardController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());
    private static final int BUTTONS_TO_SHOW = 3;
    private static final int INITIAL_PAGE = 0;
    private static final int INITIAL_PAGE_SIZE = 5;
    private static final int[] PAGE_SIZES = { 5, 10 };

    @Autowired
    private AppointmentRestClient appointment;

    @SuppressWarnings("unchecked")
    @GetMapping({ "/loadAppointments" })
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

    @SuppressWarnings("unchecked")
    @GetMapping({ "/appointmentDashboard" })
    public ModelAndView appointmentDashboard(@RequestParam("pageSize") Optional<Integer> pageSize,
                                             @RequestParam("page") Optional<Integer> page,
                                             final HttpServletRequest request) {
        ModelAndView modelAndView = new ModelAndView("appointmentDashboard");
        try {
            int evalPageSize = pageSize.orElse(INITIAL_PAGE_SIZE);
            int evalPage = (page.orElse(0) < 1) ? INITIAL_PAGE : page.get() - 1;

            List<Map<String, Object>> all = new ArrayList<>();
            Map<String, Object> resp = appointment.getMap("/appointments");
            List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
            if (data != null) {
                all = data;
            }

            int total = all.size();
            int from = Math.min(evalPage * evalPageSize, total);
            int to = Math.min(from + evalPageSize, total);
            List<Map<String, Object>> content = all.subList(from, to);
            PageImpl<Map<String, Object>> clientlist =
                    new PageImpl<>(content, PageRequest.of(evalPage, evalPageSize), total);

            PagerModel pager = new PagerModel(clientlist.getTotalPages(), clientlist.getNumber(), BUTTONS_TO_SHOW);
            modelAndView.addObject("clientlist", clientlist);
            modelAndView.addObject("selectedPageSize", evalPageSize);
            modelAndView.addObject("pageSizes", PAGE_SIZES);
            modelAndView.addObject("pager", pager);
        } catch (Exception e) {
            LOGGER.error("appointmentDashboard failed", e);
        }
        return modelAndView;
    }
}
