package com.myplus.education.controller;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.AlertChannel;
import com.myplus.education.entity.Alerts;
import com.myplus.education.repository.AlertChannelRepository;
import com.myplus.education.repository.AlertsRepository;
import com.myplus.education.repository.GuardianRepository;
import com.myplus.education.repository.StaffRepository;
import com.myplus.education.repository.StudentRepository;
import com.myplus.education.service.EmailService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Flat Alerts endpoints (slice 16) — system alerts + public-alert contacts, org-scoped, with real
 * email delivery. Every send records who/when; admin recipients are always copied (EmailService).
 */
@Controller
public class AlertController {

    @Autowired private AlertsRepository alertsRepository;
    @Autowired private AlertChannelRepository alertChannelRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private GuardianRepository guardianRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private EmailService emailService;
    @Autowired private RequestUtil requestUtil;
    @Autowired private AppUtil appUtil;

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }
    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    // ---- System alerts ----

    @RequestMapping(value = {"/getUserAlerts", "/getAllAlerts"}, method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getUserAlerts() {
        try {
            List<Alerts> objs = alertsRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(objs)) return new GenericResponse("NOT_FOUND", "");
            List<Map<String, Object>> rows = objs.stream().map(this::alertMap).collect(Collectors.toList());
            return new GenericResponse("SUCCESS", "", rows);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addAlerts", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public GenericResponse addAlerts(final HttpServletRequest request) {
        try {
            Long id = parseLong(request.getParameter("id"));
            Alerts a = (id != null) ? alertsRepository.findById(id).orElseGet(Alerts::new) : new Alerts();
            a.setUserId(userId());           // audit
            a.setOrganizationId(orgId());    // tenant scope
            a.setC(joinMulti(request, "c"));
            a.setAt(joinMulti(request, "at"));
            a.setDc(joinMulti(request, "dc"));
            a.setDp(joinMulti(request, "dp"));
            a.setDeliveryType(request.getParameter("dt"));
            a.setSt(request.getParameter("st"));
            a.setAh(request.getParameter("ah"));
            a.setAm(request.getParameter("am"));
            a.setAlertSignature(request.getParameter("as"));
            if (!appUtil.isEmptyOrNull(request.getParameter("sdStr"))) a.setSd(appUtil.getLocalDate(request.getParameter("sdStr")));
            if (!appUtil.isEmptyOrNull(request.getParameter("edStr"))) a.setEd(appUtil.getLocalDate(request.getParameter("edStr")));
            alertsRepository.save(a);
            return new GenericResponse("SUCCESS", "Alert saved");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteAlerts", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public boolean deleteAlerts(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (StringUtils.isEmpty(ids)) return false;
            for (String id : ids.split(",")) {
                if (!StringUtils.isEmpty(id)) alertsRepository.deleteById(Long.valueOf(id));
            }
            return true;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return false;
        }
    }

    @RequestMapping(value = "/sendAlerts", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public GenericResponse sendAlerts(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String heading = request.getParameter("ah");
            String message = request.getParameter("am");
            String signature = request.getParameter("as");
            String consumers = joinMulti(request, "c");
            String body = (message == null ? "" : message) + (appUtil.isEmptyOrNull(signature) ? "" : "\n\n" + signature);
            Set<String> recipients = consumerEmails(org, uid, consumers);
            Map<String, Object> result = emailService.send(heading, body, recipients);

            Long id = parseLong(request.getParameter("id"));
            if (id != null) {
                alertsRepository.findById(id).ifPresent(a -> { a.setSt("Sent"); alertsRepository.save(a); });
            }
            return new GenericResponse("SUCCESS", "Sent to " + result.get("sent") + ", failed " + result.get("failed"), result);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ---- Public alerts (contacts) ----

    @RequestMapping(value = "/getUserPA", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getUserPA() {
        try {
            List<AlertChannel> objs = alertChannelRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(objs)) return new GenericResponse("NOT_FOUND", "");
            List<Map<String, Object>> rows = objs.stream().map(this::channelMap).collect(Collectors.toList());
            return new GenericResponse("SUCCESS", "", rows);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // CSV columns: name,email,mobile,userType
    @RequestMapping(value = "/importCSV", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public GenericResponse importCSV(@RequestParam("file") MultipartFile file) {
        int created = 0;
        List<String> errors = new ArrayList<>();
        try {
            if (file == null || file.isEmpty()) return new GenericResponse("INVALID", "No file uploaded");
            Long org = orgId(), uid = userId();
            BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
            String line; int n = 0; String[] header = null;
            while ((line = br.readLine()) != null) {
                n++;
                if (line.trim().isEmpty()) continue;
                String[] cols = splitCsv(line);
                if (header == null) { header = lower(cols); continue; }
                Map<String, String> row = rowMap(header, cols);
                String name = row.get("name");
                String email = row.get("email");
                String mobile = row.get("mobile");
                String ut = row.get("usertype");
                if (appUtil.isEmptyOrNull(email) && appUtil.isEmptyOrNull(mobile)) {
                    errors.add("row " + n + ": email or mobile required"); continue;
                }
                if (!appUtil.isEmptyOrNull(email)) { saveChannel(org, uid, "Email", email, name, ut); created++; }
                if (!appUtil.isEmptyOrNull(mobile)) { saveChannel(org, uid, "Mobile", mobile, name, ut); created++; }
            }
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("created", created);
        summary.put("errors", errors);
        return new GenericResponse("SUCCESS", "Imported " + created + " contact(s)", summary);
    }

    @RequestMapping(value = "/sendPA", method = RequestMethod.POST)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse sendPA(final HttpServletRequest request) {
        try {
            String heading = request.getParameter("pah");
            String message = request.getParameter("pam");
            String signature = request.getParameter("pas");
            String body = (message == null ? "" : message) + (appUtil.isEmptyOrNull(signature) ? "" : "\n\n" + signature);
            Set<String> recipients = alertChannelRepository.findScoped(orgId(), userId()).stream()
                    .filter(c -> "Email".equalsIgnoreCase(c.getC()) && c.getCn() != null && c.getCn().contains("@"))
                    .map(AlertChannel::getCn).collect(Collectors.toCollection(LinkedHashSet::new));
            Map<String, Object> result = emailService.send(heading, body, recipients);
            return new GenericResponse("SUCCESS", "Sent to " + result.get("sent") + ", failed " + result.get("failed"), result);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ---- helpers ----
    private void saveChannel(Long org, Long uid, String channel, String value, String name, String ut) {
        AlertChannel ch = new AlertChannel();
        ch.setOrganizationId(org);
        ch.setUserId(uid);
        ch.setC(channel);
        ch.setCn(value.trim());
        ch.setUt(ut);
        ch.setS("Active");
        alertChannelRepository.save(ch);
    }

    private Set<String> consumerEmails(Long org, Long uid, String consumersCsv) {
        Set<String> emails = new LinkedHashSet<>();
        String c = consumersCsv == null ? "" : consumersCsv.toLowerCase();
        boolean all = c.contains("all");
        if (all || c.contains("student")) studentRepository.findScoped(org, uid).forEach(s -> addEmail(emails, s.getEmail()));
        if (all || c.contains("guardian")) guardianRepository.findScoped(org, uid).forEach(g -> addEmail(emails, g.getEmail()));
        if (all || c.contains("employee") || c.contains("staff")) staffRepository.findScoped(org, uid).forEach(s -> addEmail(emails, s.getEmail()));
        return emails;
    }
    private void addEmail(Set<String> set, String e) { if (e != null && e.contains("@")) set.add(e.trim()); }

    private Map<String, Object> alertMap(Alerts a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("consumers", a.getC());
        m.put("alertType", a.getAt());
        m.put("deliveryChannel", a.getDc());
        m.put("deliveryPeriod", a.getDp());
        m.put("deliveryType", a.getDeliveryType());
        m.put("heading", a.getAh());
        m.put("message", a.getAm());
        m.put("signature", a.getAlertSignature());
        m.put("startDateStr", appUtil.getLocalDateStr(a.getSd()));
        m.put("endDateStr", appUtil.getLocalDateStr(a.getEd()));
        m.put("status", a.getSt());
        return m;
    }

    private Map<String, Object> channelMap(AlertChannel c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("datedStr", appUtil.getDateStr(c.getDt() == null ? null : c.getDt()));
        m.put("channel", c.getC());
        m.put("target", c.getCn());
        m.put("userType", c.getUt());
        m.put("status", c.getS());
        return m;
    }

    private String joinMulti(HttpServletRequest req, String name) {
        String[] vals = req.getParameterValues(name);
        if (vals == null) return null;
        List<String> kept = new ArrayList<>();
        for (String v : vals) if (v != null && !v.trim().isEmpty()) kept.add(v.trim());
        return String.join(",", kept);
    }
    private Long parseLong(String s) { try { return (s == null || s.isBlank()) ? null : Long.valueOf(s.trim()); } catch (Exception e) { return null; } }
    private String[] splitCsv(String line) {
        String[] parts = line.split(",", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim().replaceAll("^\"|\"$", "").trim();
        return parts;
    }
    private String[] lower(String[] cols) {
        String[] out = new String[cols.length];
        for (int i = 0; i < cols.length; i++) out[i] = cols[i] == null ? "" : cols[i].toLowerCase();
        return out;
    }
    private Map<String, String> rowMap(String[] header, String[] cols) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) m.put(header[i], i < cols.length ? cols[i] : "");
        return m;
    }
}
