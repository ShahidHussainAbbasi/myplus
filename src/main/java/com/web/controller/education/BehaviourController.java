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
 * Slice 2.5 — thin proxy to education-service's behaviour-log endpoints.
 * Design: microservices/docs/slices/edu-2.5-discipline-log.md
 *
 * No logic here by design. Note that there are only THREE endpoints: read, record, supersede. There is no
 * edit and no delete anywhere in the stack — immutability is enforced by the operations not existing, not
 * by a check that a future change could relax.
 */
@Controller
public class BehaviourController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    @RequestMapping(value = "/getBehaviourNotes", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getBehaviourNotes(HttpServletRequest request) {
        // An enrolment number is free text, so it is URL-encoded rather than digit-validated.
        String en = request.getParameter("enrollNo");
        String path = "/getBehaviourNotes?enrollNo="
                + URLEncoder.encode(en == null ? "" : en, StandardCharsets.UTF_8);
        return educationClient.get(path, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/saveBehaviourNote", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> saveBehaviourNote(HttpServletRequest request) {
        return educationClient.post("/saveBehaviourNote", request, requestUtil.getCurrentUser().getId());
    }

    @RequestMapping(value = "/supersedeBehaviourNote", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> supersedeBehaviourNote(HttpServletRequest request) {
        return educationClient.post("/supersedeBehaviourNote", request, requestUtil.getCurrentUser().getId());
    }
}
