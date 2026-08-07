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
 * Slice 3.5 — thin proxy for the STAFF side of notices: write, publish, delete.
 * Design: microservices/docs/slices/edu-3.5-notices.md
 *
 * <p>Deliberately a different class from the portal proxies. Publishing is ADMIN_PRIVILEGE and is enforced
 * in the service; keeping the two apart means a portal session is never one routing mistake away from an
 * endpoint that addresses the whole school — the same separation 3.1 made for invite/revoke.
 */
@Controller
public class NoticeController {

    @Autowired
    EducationRestClient educationClient;

    @Autowired
    RequestUtil requestUtil;

    private Long caller() {
        return requestUtil.getCurrentUser().getId();
    }

    @RequestMapping(value = "/getNotices", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getNotices() {
        return educationClient.get("/getNotices", caller());
    }

    /** The recipient count shown BEFORE publishing — see the service's javadoc for why it exists. */
    @RequestMapping(value = "/getNoticeReach", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getNoticeReach(HttpServletRequest request) {
        String audience = request.getParameter("audience");
        String gradeId = request.getParameter("gradeId");
        String path = "/getNoticeReach?audience="
                + URLEncoder.encode(audience == null ? "" : audience, StandardCharsets.UTF_8)
                + "&gradeId=" + URLEncoder.encode(gradeId == null ? "" : gradeId, StandardCharsets.UTF_8);
        return educationClient.get(path, caller());
    }

    @RequestMapping(value = "/saveNotice", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> saveNotice(HttpServletRequest request) {
        return educationClient.post("/saveNotice", request, caller());
    }

    @RequestMapping(value = "/publishNotice", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> publishNotice(HttpServletRequest request) {
        return educationClient.post("/publishNotice", request, caller());
    }

    @RequestMapping(value = "/deleteNotice", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> deleteNotice(HttpServletRequest request) {
        return educationClient.post("/deleteNotice", request, caller());
    }
}
