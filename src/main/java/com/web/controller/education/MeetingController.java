package com.web.controller.education;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.EducationRestClient;
import com.web.util.RequestUtil;

/**
 * Slice edu-3.4 — thin proxy for the STAFF side of guardian–teacher meetings.
 * Design: microservices/docs/slices/edu-3.4-guardian-teacher-meetings.md
 *
 * <p>Separate from the portal proxy on purpose, the same separation 3.1 and 3.5 made: publishing an
 * evening and opening it for booking are ADMIN acts enforced in the service, and a family's session must
 * never be one routing mistake away from them.
 *
 * <p>No logic lives here. The slots themselves are in the shared scheduling core (SCHED-1) and are reached
 * by education-service, not by this proxy — the monolith never speaks to the scheduling API directly.
 */
@Controller
public class MeetingController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    private Long caller() {
        return requestUtil.getCurrentUser().getId();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    @RequestMapping(value = "/getMeetingEvents", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getMeetingEvents() {
        return educationClient.get("/getMeetingEvents", caller());
    }

    @RequestMapping(value = "/getMeetingSlots", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getMeetingSlots(HttpServletRequest request) {
        return educationClient.get("/getMeetingSlots?eventId=" + enc(request.getParameter("eventId")), caller());
    }

    @RequestMapping(value = "/saveMeetingEvent", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> saveMeetingEvent(HttpServletRequest request) {
        return educationClient.post("/saveMeetingEvent", request, caller());
    }

    @RequestMapping(value = "/publishMeetingSlots", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> publishMeetingSlots(HttpServletRequest request) {
        return educationClient.post("/publishMeetingSlots", request, caller());
    }

    @RequestMapping(value = "/setMeetingEventStatus", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> setMeetingEventStatus(HttpServletRequest request) {
        return educationClient.post("/setMeetingEventStatus", request, caller());
    }
}
