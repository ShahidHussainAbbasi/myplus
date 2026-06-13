package com.myplus.education.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.dto.AttendanceDTO;
import com.myplus.education.dto.BulkAttendanceRequest;
import com.myplus.education.entity.Attendance;
import com.myplus.education.entity.Grade;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.AttendanceRepository;
import com.myplus.education.repository.GradeRepository;
import com.myplus.education.repository.StudentRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Flat Attendance endpoints — list/delete plus class-roster marking (slice 13). Org-scoped.
 * Each marked row records who marked it (user_id) for teacher-activity analytics.
 */
@Controller
public class AttendanceController {

    @Autowired
    private AttendanceRepository attendanceRepository;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private GradeRepository gradeRepository;
    @Autowired
    private RequestUtil requestUtil;
    @Autowired
    private AppUtil appUtil;

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    /** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    private AttendanceDTO toDto(Attendance a) {
        AttendanceDTO dto = new AttendanceDTO();
        dto.setId(a.getId());
        dto.setUserId(a.getUserId());
        dto.setEn(a.getEn());
        dto.setSn(a.getSn());
        dto.setGrid(a.getGrid());
        dto.setGn(a.getGn());
        dto.setStatus(a.getStatus());
        dto.setDt(a.getDt());
        dto.setIn(a.getIn());
        dto.setOut(a.getOut());
        dto.setRem(a.getRem());
        dto.setDtStr(appUtil.getLocalDateTimeStr(a.getDt()));
        return dto;
    }

    @RequestMapping(value = "/getUserA", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserA(final HttpServletRequest request) {
        try {
            List<Attendance> objs = attendanceRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getAllA", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllA(final HttpServletRequest request) {
        try {
            // Tenant-scoped: "all" means all attendance in the active organization, not every tenant's.
            List<Attendance> all = attendanceRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteA", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteA(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                for (String id : ids.split(",")) {
                    if (!StringUtils.isEmpty(id)) {
                        attendanceRepository.deleteById(Long.valueOf(id));
                    }
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return false;
        }
    }

    // ---- Slice 13: class-roster marking ----

    /** enrollNo -> {name, gradeId, gradeName} for the active org (client-side lookups). */
    @RequestMapping(value = "/getUserStudentMap", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getUserStudentMap() {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Student s : studentRepository.findScoped(orgId(), userId())) {
                if (appUtil.isEmptyOrNull(s.getEnrollNo())) continue;
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("name", s.getName());
                v.put("gradeId", s.getGradeId());
                v.put("gradeName", gradeName(s.getGradeId()));
                map.put(s.getEnrollNo(), v);
            }
            if (map.isEmpty()) return new GenericResponse("NOT_FOUND", "");
            return new GenericResponse("SUCCESS", "", map);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** The class's students (org-scoped) with any existing marks for the given day pre-filled. */
    @RequestMapping(value = "/getClassRoster", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getClassRoster(@RequestParam(value = "gradeId", required = false) Long gradeId,
                                          @RequestParam(value = "dateStr", required = false) String dateStr) {
        try {
            if (appUtil.isEmptyOrNull(gradeId)) {
                return new GenericResponse("INVALID", "Please select a class");
            }
            LocalDate date = appUtil.isEmptyOrNull(dateStr) ? LocalDate.now() : appUtil.getLocalDate(dateStr);

            Map<String, Attendance> existing = new LinkedHashMap<>();
            for (Attendance a : attendanceRepository.findByOrganizationIdAndAttDate(orgId(), date)) {
                if (a.getEn() != null) existing.put(a.getEn(), a);
            }

            String gn = gradeName(gradeId);
            List<Map<String, Object>> roster = new ArrayList<>();
            for (Student s : studentRepository.findScoped(orgId(), userId())) {
                if (!Objects.equals(s.getGradeId(), gradeId)) continue;
                Attendance a = existing.get(s.getEnrollNo());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("enrollNo", s.getEnrollNo());
                row.put("studentName", s.getName());
                row.put("gradeId", gradeId);
                row.put("gradeName", gn);
                row.put("status", a != null && a.getStatus() != null ? a.getStatus() : "Present");
                row.put("timeInStr", a != null && a.getIn() != null ? a.getIn().toString() : "");
                row.put("timeOutStr", a != null && a.getOut() != null ? a.getOut().toString() : "");
                row.put("remark", a != null ? a.getRem() : "");
                roster.add(row);
            }
            if (roster.isEmpty()) return new GenericResponse("NOT_FOUND", "No students in this class");
            return new GenericResponse("SUCCESS", "", roster);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Mark a whole class roster in one request — upsert one row per student per day. */
    @RequestMapping(value = "/markAttendanceBulk", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public GenericResponse markAttendanceBulk(@RequestBody BulkAttendanceRequest req) {
        try {
            if (req == null || appUtil.isEmptyOrNull(req.getRows())) {
                return new GenericResponse("INVALID", "Nothing to save");
            }
            Long org = orgId();
            Long uid = userId();
            LocalDate date = appUtil.isEmptyOrNull(req.getDateStr()) ? LocalDate.now() : appUtil.getLocalDate(req.getDateStr());
            String gn = gradeName(req.getGradeId());

            Map<String, Student> students = new LinkedHashMap<>();
            for (Student s : studentRepository.findScoped(org, uid)) {
                if (s.getEnrollNo() != null) students.put(s.getEnrollNo(), s);
            }

            int saved = 0;
            for (BulkAttendanceRequest.Row r : req.getRows()) {
                if (r == null || appUtil.isEmptyOrNull(r.getEnrollNo())) continue;
                Attendance a = attendanceRepository
                        .findFirstByOrganizationIdAndEnAndAttDate(org, r.getEnrollNo(), date)
                        .orElseGet(Attendance::new);
                Student s = students.get(r.getEnrollNo());
                a.setOrganizationId(org);       // tenant scope
                a.setUserId(uid);               // audit: who marked it (teacher-activity analytics)
                a.setEn(r.getEnrollNo());
                a.setSn(s != null ? s.getName() : a.getSn());
                a.setGrid(req.getGradeId() != null ? req.getGradeId() : (s != null ? s.getGradeId() : a.getGrid()));
                a.setGn(gn);
                a.setStatus(appUtil.isEmptyOrNull(r.getStatus()) ? "Present" : r.getStatus());
                a.setRem(r.getRemark());
                a.setIn(appUtil.isEmptyOrNull(r.getTimeInStr()) ? null : LocalTime.parse(r.getTimeInStr()));
                a.setOut(appUtil.isEmptyOrNull(r.getTimeOutStr()) ? null : LocalTime.parse(r.getTimeOutStr()));
                a.setAttDate(date);
                a.setDt(LocalDateTime.now());
                attendanceRepository.save(a);
                saved++;
            }
            return new GenericResponse("SUCCESS", saved + " record(s) saved");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            // Propagate past the @Transactional proxy so the partial batch is rolled back
            // (returning ERROR here would commit it). bulkErrorHandler() rebuilds the envelope.
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Turns an uncaught exception from a transactional write (e.g. markAttendanceBulk) back into the
     * GenericResponse("ERROR", …) envelope. By the time this runs the @Transactional method has already
     * exited via exception, so its transaction has been rolled back — the write is all-or-nothing.
     */
    @ExceptionHandler(Exception.class)
    @ResponseBody
    public GenericResponse handleUncaught(Exception e) {
        appUtil.le(getClass(), e);
        return new GenericResponse("ERROR", e.getMessage());
    }

    private String gradeName(Long gradeId) {
        if (appUtil.isEmptyOrNull(gradeId)) return "";
        Grade g = gradeRepository.findById(gradeId).orElse(null);
        return g == null ? "" : g.getName();
    }
}
