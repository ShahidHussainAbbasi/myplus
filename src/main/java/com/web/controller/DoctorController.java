package com.web.controller;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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

import com.web.dto.DoctorDTO;
import com.web.util.AppointmentRestClient;
import com.web.util.GenericResponse;

/** Doctor screens. Proxies to appointment-service via {@link AppointmentRestClient} — no local DB. */
@Controller
public class DoctorController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private MessageSource messages;

    @Autowired
    private AppointmentRestClient appointment;

    @SuppressWarnings("unchecked")
    @RequestMapping(value = "/loadDoctorDetails", method = RequestMethod.GET)
    @ResponseBody
    public String loadDoctorDetails(@RequestParam Long doctorId) {
        try {
            Map<String, Object> resp = appointment.getMap("/doctors/" + doctorId);
            Map<String, Object> d = (Map<String, Object>) resp.get("data");
            StringBuffer sb = new StringBuffer();
            sb.append("<p id='schedule'>Days From : " + str(d.get("dayFrom")) + " To " + str(d.get("dayTo")) + " <br/>");
            sb.append("Time From : " + str(d.get("timeIn")) + " To " + str(d.get("timeOut")) + " <br/>");
            sb.append("Specialist : " + str(d.get("speciality")) + " </p>");
            return sb.toString();
        } catch (Exception e) {
            LOGGER.error("loadDoctorDetails failed", e);
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    @RequestMapping(value = "/loadDoctorsByHospital", method = RequestMethod.GET)
    @ResponseBody
    public String loadDoctorsByHospital(@RequestParam Long hospitalId) {
        StringBuffer sb = new StringBuffer();
        sb.append("<option value=''> Select Doctor </option>");
        try {
            Map<String, Object> resp = appointment.getMap("/doctors?hospitalId=" + hospitalId);
            List<Map<String, Object>> doctors = (List<Map<String, Object>>) resp.get("data");
            if (doctors != null) {
                for (Map<String, Object> d : doctors) {
                    sb.append("<option value='" + str(d.get("id")) + "'>" + str(d.get("name")) + "</option>");
                }
            }
        } catch (Exception e) {
            LOGGER.error("loadDoctorsByHospital failed", e);
        }
        return sb.toString();
    }

    @RequestMapping(value = "/registerDoctor", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse registerDoctor(final DoctorDTO doctorDto, final HttpServletRequest request) {
        try {
            LOGGER.debug("Registering doctor: {}", doctorDto.getName());
            Map<String, Object> body = new HashMap<>();
            body.put("hospitalId", doctorDto.getHospitalId());
            body.put("name", doctorDto.getName());
            body.put("speciality", doctorDto.getSpeciality());
            body.put("email", doctorDto.getEmail());
            body.put("mobile", doctorDto.getMobile());
            body.put("address", doctorDto.getAddress());
            body.put("availabe", doctorDto.getAvailabe());
            body.put("dayFrom", doctorDto.getDayFrom());
            body.put("dayTo", doctorDto.getDayTo());
            body.put("timeIn", doctorDto.getTimeIn());
            body.put("timeOut", doctorDto.getTimeOut());
            body.put("appointmentOfferType", doctorDto.getAppointmentOfferType());
            body.put("appointmentOfferValue", doctorDto.getAppointmentOfferValue());
            appointment.postJson("/doctors", body);
            return new GenericResponse("Doctor registered successfully");
        } catch (Exception e) {
            LOGGER.error("registerDoctor failed", e);
            return new GenericResponse(
                    messages.getMessage("message.userNotFound", null, request.getLocale()), "RegisterFailed");
        }
    }

    @SuppressWarnings("unchecked")
    @RequestMapping(value = "/addDoctor", method = RequestMethod.GET)
    public String addDoctor(final Locale locale, final Model model) {
        Map<Long, String> hospitalMap = new HashMap<>();
        try {
            Map<String, Object> resp = appointment.getMap("/hospitals");
            List<Map<String, Object>> hospitals = (List<Map<String, Object>>) resp.get("data");
            if (hospitals != null) {
                for (Map<String, Object> h : hospitals) {
                    hospitalMap.put(((Number) h.get("id")).longValue(), str(h.get("name")));
                }
            }
        } catch (Exception e) {
            LOGGER.error("addDoctor hospital load failed", e);
        }
        model.addAttribute("days", Arrays.asList("All", "Monday", "Tuesday", "Wednesday", "Thursday", "Fiday"));
        model.addAttribute("hospitals", hospitalMap);
        return "doctor";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
