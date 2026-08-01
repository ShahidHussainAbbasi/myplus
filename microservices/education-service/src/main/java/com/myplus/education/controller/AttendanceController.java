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
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.myplus.education.util.ScopedDeleter;

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
    private com.myplus.education.service.StudentVisibilityService studentVisibilityService;

    @Autowired
    private ScopedDeleter scopedDeleter;   // anti-IDOR bulk delete
    @Autowired
    private AppUtil appUtil;
    @Autowired
    private com.myplus.education.service.TermService termService;   // slice 1.1 — current-term stamping

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

    /**
     * P4 within-tenant leak fix: attendance belongs to the student it is for (by enrollNo), so a
     * branch-constrained caller (a teacher with branch grants) must see attendance only for students in their
     * accessible branches. Owner/super, or a caller with no grants (single-branch / legacy), see org-wide —
     * unchanged. Rows whose enrollNo maps to a student in another branch are hidden; orphaned null-enroll rows
     * stay visible to their tenant (the store_id-NULL fallback convention).
     */
    private List<Attendance> branchVisible(List<Attendance> rows) {
        if (requestUtil.isOwnerSuper()) return rows;
        java.util.Set<Long> schools = requestUtil.accessibleSchoolIds();
        if (schools.isEmpty()) return rows;
        java.util.Set<String> visibleEn = studentRepository.findScopedBySchools(orgId(), schools).stream()
                .map(Student::getEnrollNo).filter(Objects::nonNull).collect(Collectors.toSet());
        return rows.stream().filter(a -> a.getEn() == null || visibleEn.contains(a.getEn()))
                .collect(Collectors.toList());
    }

    /** The students the caller may see for roster/marking: owner/super or no-grants ⇒ org-wide (unchanged);
     *  a branch-constrained teacher ⇒ only their branches' students. Mirrors StudentController.visibleStudents. */
    private List<Student> visibleStudents() {
        return studentVisibilityService.visibleStudents(orgId(), userId());
    }

    @RequestMapping(value = "/getUserA", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserA(final HttpServletRequest request) {
        try {
            List<Attendance> objs = branchVisible(attendanceRepository.findScoped(orgId(), userId()));
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
            // Tenant- AND branch-scoped: a branch-constrained caller sees only their branches' attendance.
            List<Attendance> all = branchVisible(attendanceRepository.findScoped(orgId(), userId()));
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @RequestMapping(value = "/deleteA", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteA(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                // Anti-IDOR: only rows in the caller's own tenant are deleted (see ScopedDeleter).
                scopedDeleter.deleteScoped(attendanceRepository, ids,
                        Attendance::getOrganizationId, Attendance::getUserId, null);
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
            for (Student s : visibleStudents()) {   // branch-scoped: a teacher's lookup map holds only their students
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
            // Branch-scoped: a teacher only rosters their branches' students. Passing another branch's gradeId
            // yields no matches (those students aren't in the visible set) — no cross-branch roster leak.
            for (Student s : visibleStudents()) {
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
    // D-3 privilege map: day-to-day record; a read-only or guest role must not write
    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
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

            // Slice 1.1 (D4): resolve the current term ONCE per batch, never per row — a 40-student
            // class would otherwise re-read every term 40 times. Null is a legitimate answer (the
            // school has not defined terms) and stamps a null term_id, exactly as before this slice.
            final Long currentTermId = termService.currentTermId(org, uid);

            // Only students the caller may see (branch-scoped). A row for a student outside the caller's
            // branches (or a non-existent enrollNo) is skipped below — a teacher cannot mark another branch.
            Map<String, Student> students = new LinkedHashMap<>();
            for (Student s : visibleStudents()) {
                if (s.getEnrollNo() != null) students.put(s.getEnrollNo(), s);
            }

            int saved = 0;
            for (BulkAttendanceRequest.Row r : req.getRows()) {
                if (r == null || appUtil.isEmptyOrNull(r.getEnrollNo())) continue;
                Student s = students.get(r.getEnrollNo());
                if (s == null) continue;   // not a student this caller may mark — skip (was: marked regardless)
                Attendance a = attendanceRepository
                        .findFirstByOrganizationIdAndEnAndAttDate(org, r.getEnrollNo(), date)
                        .orElseGet(Attendance::new);
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
                // Stamp only when the row has no term yet: new rows get one, and a row written before
                // terms existed picks one up when re-marked. An existing stamp is never rewritten, so
                // re-saving today's register cannot silently move it into another term.
                if (a.getTermId() == null) a.setTermId(currentTermId);
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
    // A @PreAuthorize denial throws AccessDeniedException; this controller's broad Exception handler below
    // would otherwise swallow it into a 200 "ERROR" envelope. A more-specific handler wins → clean 403.
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    @ResponseBody
    public org.springframework.http.ResponseEntity<GenericResponse> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException e) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                .body(new GenericResponse("FORBIDDEN", "Access denied"));
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public GenericResponse handleUncaught(Exception e) {
        appUtil.le(getClass(), e);
        return new GenericResponse("ERROR", e.getMessage());
    }

    /**
     * Display-only class name. Scoped: the id reaches here from a request parameter, so an unscoped
     * lookup would confirm another tenant's class name back to the caller — small, but it is still
     * their data leaking through an attendance screen.
     */
    private String gradeName(Long gradeId) {
        if (appUtil.isEmptyOrNull(gradeId)) return "";
        Grade g = gradeRepository.findByIdScoped(gradeId, orgId(), userId()).orElse(null);
        return g == null ? "" : g.getName();
    }
}
