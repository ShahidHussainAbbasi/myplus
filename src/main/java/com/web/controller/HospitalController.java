package com.web.controller;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.dto.HospitalDto;
import com.web.util.AppUtil;
import com.web.util.AppointmentRestClient;
import com.web.util.GenericResponse;

/**
 * Hospital screens. Proxies to appointment-service via {@link AppointmentRestClient} — no local DB.
 * Country options come from the static {@link AppUtil#countryMap}; state/city are entered free-form
 * (geo is static client-side per the slice-17 design).
 */
@Controller
public class HospitalController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private MessageSource messages;

    @Autowired
    private AppointmentRestClient appointment;

    @Autowired
    private AppUtil appUtil;

    @RequestMapping(value = "/registerHospital", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse registerHospital(final HospitalDto hospitalDto, final HttpServletRequest request) {
        try {
            LOGGER.debug("Registering hospital: {}", hospitalDto);
            Map<String, Object> body = new HashMap<>();
            body.put("name", hospitalDto.getName());
            body.put("email", hospitalDto.getEmail());
            body.put("phone", hospitalDto.getPhone());
            body.put("logoUrl", hospitalDto.getLogoUrl());
            body.put("country", hospitalDto.getCountryCode());
            body.put("state", hospitalDto.getState());
            body.put("city", hospitalDto.getGeoId());
            appointment.postJson("/hospitals", body);
            return new GenericResponse("Hospital registered successfully");
        } catch (com.web.error.DemoLimitException e) {
            throw e; // let DemoLimitAdvice return the upsell instead of a generic failure
        } catch (Exception e) {
            LOGGER.error("registerHospital failed", e);
            return new GenericResponse(
                    messages.getMessage("message.userNotFound", null, request.getLocale()), "RegisterFailed");
        }
    }

    @RequestMapping(value = "/addHospital", method = RequestMethod.GET)
    public String addHospital(final Locale locale, final Model model) {
        model.addAttribute("countries", appUtil.countryMap);
        return "hospital";
    }

    /** Hospitals for the logged-in org as JSON ({@code [{id,name}]}) — dashboard/booking dropdowns. */
    @SuppressWarnings("unchecked")
    @RequestMapping(value = "/loadHospitals", method = RequestMethod.GET)
    @ResponseBody
    public java.util.List<Map<String, Object>> loadHospitals() {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        try {
            Map<String, Object> resp = appointment.getMap("/hospitals");
            java.util.List<Map<String, Object>> hospitals = (java.util.List<Map<String, Object>>) resp.get("data");
            if (hospitals != null) {
                for (Map<String, Object> h : hospitals) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", h.get("id"));
                    item.put("name", h.get("name"));
                    out.add(item);
                }
            }
        } catch (Exception e) {
            LOGGER.error("loadHospitals failed", e);
        }
        return out;
    }

    @RequestMapping(value = "/loadStatesByCountry", method = RequestMethod.GET)
    @ResponseBody
    public String loadStatesByCountry(@RequestParam String countryCode) {
        // Geo is static client-side now; states are free-form. Default option only.
        return "<option value='-1'> Select State </option>";
    }

    @RequestMapping(value = "/loadCitiesByState", method = RequestMethod.GET)
    @ResponseBody
    public String loadCitiesByState(@RequestParam String state) {
        return "<option value='-1'> Select City </option>";
    }
}
