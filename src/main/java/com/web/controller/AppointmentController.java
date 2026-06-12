package com.web.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.servlet.ModelAndView;

import com.web.dto.AppointmentDTO;
import com.web.util.AppointmentRestClient;
import com.web.util.GenericResponse;

/** Appointment screens. Public booking + hospital list proxy to appointment-service — no local DB. */
@Controller
public class AppointmentController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");

    @Autowired
    private MessageSource messages;

    @Autowired
    private AppointmentRestClient appointment;

    @RequestMapping(value = "/appointmentReq", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse appointmentReq(@Validated final AppointmentDTO appointmentDTO, final HttpServletRequest request) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("hospitalId", appointmentDTO.getHospitalId());
            body.put("doctorId", appointmentDTO.getDoctorId());
            body.put("patientName", appointmentDTO.getName());
            body.put("patientPhone", appointmentDTO.getMobile());
            body.put("patientEmail", appointmentDTO.getEmail());
            body.put("patientAddress", appointmentDTO.getAddress());

            Map<String, Object> resp = appointment.postPublic("/public/appointment-request", body);
            Object data = resp != null ? resp.get("data") : null;
            Integer appntmntNo = null;
            if (data instanceof Map) {
                Object n = ((Map<?, ?>) data).get("patientsAppointed");
                if (n instanceof Number) {
                    appntmntNo = ((Number) n).intValue();
                }
            }
            GenericResponse genericResponse = new GenericResponse();
            genericResponse.setStatus("SUCCESS");
            if (appntmntNo != null) {
                genericResponse.setMessage("Dear " + appointmentDTO.getName() + ", your appointment number "
                        + appntmntNo + " is registered. We will contact you on " + appointmentDTO.getMobile() + ".");
            } else {
                genericResponse.setMessage("Appointment registered successfully.");
            }
            return genericResponse;
        } catch (HttpStatusCodeException e) {
            // appointment-service returns 400 with a business message (blocked / daily limit reached).
            String msg = extractMessage(e.getResponseBodyAsString());
            GenericResponse fail = new GenericResponse();
            fail.setStatus("FAILURE");
            fail.setError(msg != null ? msg : "Could not book the appointment.");
            return fail;
        } catch (Exception e) {
            LOGGER.error("appointmentReq failed", e);
            GenericResponse fail = new GenericResponse();
            fail.setStatus("FAILURE");
            fail.setError(messages.getMessage("message.userNotFound", null, request.getLocale()));
            return fail;
        }
    }

    @SuppressWarnings("unchecked")
    @RequestMapping(value = "/appointment", method = RequestMethod.GET)
    public ModelAndView appointment(final Locale locale, final Model model) {
        java.util.List<Map<String, Object>> hospitalList = new java.util.ArrayList<>();
        try {
            Map<String, Object> resp = appointment.getMap("/hospitals");
            List<Map<String, Object>> hospitals = (List<Map<String, Object>>) resp.get("data");
            if (hospitals != null) {
                for (Map<String, Object> h : hospitals) {
                    // expose legacy property names the template reads (hospital.hospitalId, hospital.name)
                    Map<String, Object> item = new HashMap<>();
                    item.put("hospitalId", h.get("id"));
                    item.put("name", h.get("name"));
                    hospitalList.add(item);
                }
            }
        } catch (Exception e) {
            LOGGER.error("appointment hospital load failed", e);
        }
        model.addAttribute("hospitals", hospitalList);
        return new ModelAndView("appointment");
    }

    private static String extractMessage(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = MESSAGE.matcher(body);
        return m.find() ? m.group(1) : null;
    }
}
