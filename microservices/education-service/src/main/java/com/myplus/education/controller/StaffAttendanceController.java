package com.myplus.education.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.settings.SettingsService;
import com.myplus.education.entity.*;
import com.myplus.education.repository.*;
import com.myplus.education.service.StaffAbsenceService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 2.3 — the staff daily register.
 * Design: microservices/docs/slices/edu-2.3-staff-attendance-leave.md
 *
 * <p>Follows the shape the student register already proves — a date, the list, one status each, saved in a
 * batch — while fixing the two things that register got wrong: no UNIQUE key (a check-then-act race) and
 * cryptic column names.
 *
 * <p><b>D3, the convergence:</b> marking someone ABSENT or on LEAVE writes 2.2's {@code StaffAbsence}
 * through {@link StaffAbsenceService}, so their lessons appear on the cover screen immediately. Correcting
 * them back to PRESENT clears it, cancelling any substitutions. One absence concept, one owner.
 */
@Controller
public class StaffAttendanceController {

    /** D2 — how late is "late". A policy, so it is per-org rather than a constant. */
    public static final String GRACE_MINUTES = "edu.attendance.staffGraceMinutes";
    public static final int GRACE_DEFAULT = 15;

    @Autowired private StaffAttendanceRepository staffAttendanceRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private StaffAbsenceService staffAbsenceService;
    @Autowired private SettingsService settingsService;
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

    private static LocalDate parseDate(String s) {
        if (!StringUtils.hasText(s)) return LocalDate.now();
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return LocalDate.now(); }
    }

    private static LocalTime parseTime(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return LocalTime.parse(s.trim()); } catch (Exception e) { return null; }
    }

    /** The JSON body of a register save — a row list, the same shape as {@code markAttendanceBulk}. */
    public static class BulkRegisterRequest {
        private String dateStr;
        private List<Row> rows;

        public String getDateStr() { return dateStr; }
        public void setDateStr(String v) { this.dateStr = v; }
        public List<Row> getRows() { return rows; }
        public void setRows(List<Row> v) { this.rows = v; }

        public static class Row {
            private Long staffId;
            private String status;
            private String timeIn;
            private String timeOut;
            private String remarks;
            public Long getStaffId() { return staffId; }
            public void setStaffId(Long v) { this.staffId = v; }
            public String getStatus() { return status; }
            public void setStatus(String v) { this.status = v; }
            public String getTimeIn() { return timeIn; }
            public void setTimeIn(String v) { this.timeIn = v; }
            public String getTimeOut() { return timeOut; }
            public void setTimeOut(String v) { this.timeOut = v; }
            public String getRemarks() { return remarks; }
            public void setRemarks(String v) { this.remarks = v; }
        }
    }

    // ── the register ────────────────────────────────────────────────────────────────────────────

    /**
     * The whole staff list for a date, with whatever is already marked.
     *
     * <p>Two queries regardless of headcount: the staff list and the day's rows, indexed in memory. Never
     * a lookup per person — the batch-not-per-row discipline from 1.1, 1.4 and 1.5.
     */
    @RequestMapping(value = "/getStaffRegister", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getStaffRegister(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            LocalDate date = parseDate(request.getParameter("date"));

            Map<Long, StaffAttendance> marked = new HashMap<>();
            for (StaffAttendance a : staffAttendanceRepository.findByDateScoped(date, org, uid)) {
                marked.put(a.getStaffId(), a);
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            for (Staff s : staffRepository.findScoped(org, uid)) {
                StaffAttendance a = marked.get(s.getId());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("staffId", s.getId());
                m.put("staffName", s.getName());
                m.put("designation", s.getDesignation());
                // The contracted start, so the screen can show what "late" is measured against.
                m.put("contractedIn", s.getTimeIn() == null ? null : s.getTimeIn().toString());
                m.put("status", a == null ? null : a.getStatus().name());
                m.put("timeIn", a == null || a.getTimeIn() == null ? null : a.getTimeIn().toString());
                m.put("timeOut", a == null || a.getTimeOut() == null ? null : a.getTimeOut().toString());
                m.put("remarks", a == null ? null : a.getRemarks());
                rows.add(m);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("date", date.toString());
            out.put("rows", rows);
            out.put("graceMinutes", graceMinutes());
            out.put("marked", marked.size());
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Save a whole register.
     *
     * <p>Upserts per row — but the <b>UNIQUE key</b> on {@code (org, staff_id, att_date)} is what actually
     * guarantees one row per person per day. The student register has no such key and relies on the
     * pre-check alone, which is a check-then-act race under two concurrent saves; that is the defect this
     * table was designed not to inherit.
     */
    @RequestMapping(value = "/markStaffAttendanceBulk", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse markStaffAttendanceBulk(@RequestBody BulkRegisterRequest req) {
        try {
            if (req == null || req.getRows() == null || req.getRows().isEmpty()) {
                return new GenericResponse("INVALID", "Nothing to save");
            }
            Long org = orgId(), uid = userId();
            LocalDate date = parseDate(req.getDateStr());

            // Staff and existing rows read ONCE for the batch, not once per row.
            Map<Long, Staff> staffById = new HashMap<>();
            for (Staff s : staffRepository.findScoped(org, uid)) staffById.put(s.getId(), s);

            List<Long> ids = new ArrayList<>();
            for (BulkRegisterRequest.Row r : req.getRows()) if (r.getStaffId() != null) ids.add(r.getStaffId());
            Map<Long, StaffAttendance> existing = new HashMap<>();
            if (!ids.isEmpty()) {
                for (StaffAttendance a : staffAttendanceRepository
                        .findByDateAndStaffScoped(date, ids, org, uid)) {
                    existing.put(a.getStaffId(), a);
                }
            }

            int saved = 0, absencesOpened = 0, absencesCleared = 0;
            List<String> problems = new ArrayList<>();

            for (BulkRegisterRequest.Row r : req.getRows()) {
                if (r.getStaffId() == null || !StringUtils.hasText(r.getStatus())) continue;
                Staff staff = staffById.get(r.getStaffId());
                if (staff == null) {
                    // Out of scope or not a staff member — skipped silently in the count, as 1.3 D3 does.
                    continue;
                }
                StaffAttendanceStatus status;
                try {
                    status = StaffAttendanceStatus.valueOf(r.getStatus().trim().toUpperCase(Locale.ROOT));
                } catch (Exception e) {
                    problems.add(staff.getName() + ": unrecognised status");
                    continue;
                }

                LocalTime timeIn = parseTime(r.getTimeIn());
                // LATE is DERIVED, never typed: one org-wide threshold instead of a judgement per row.
                if (status == StaffAttendanceStatus.PRESENT && timeIn != null && staff.getTimeIn() != null
                        && timeIn.isAfter(staff.getTimeIn().plusMinutes(graceMinutes()))) {
                    status = StaffAttendanceStatus.LATE;
                }

                StaffAttendance row = existing.get(r.getStaffId());
                if (row == null) {
                    row = StaffAttendance.builder()
                            .staffId(r.getStaffId()).staffName(staff.getName()).attDate(date)
                            .userId(uid).organizationId(org).dated(LocalDateTime.now())
                            .build();
                }
                row.setStatus(status);
                row.setTimeIn(timeIn);
                row.setTimeOut(parseTime(r.getTimeOut()));
                row.setRemarks(StringUtils.hasText(r.getRemarks()) ? r.getRemarks().trim() : null);
                row.setUpdated(LocalDateTime.now());
                staffAttendanceRepository.save(row);
                saved++;

                // ── D3: the register is one of the paths into StaffAbsence ──────────────────────────
                boolean out = status == StaffAttendanceStatus.ABSENT || status == StaffAttendanceStatus.LEAVE;
                if (out) {
                    absencesOpened += staffAbsenceService.openAbsence(org, uid, r.getStaffId(),
                            staff.getName(), date, r.getRemarks(), null) > 0 ? 1 : 0;
                } else {
                    // Corrected to present: the absence AND its substitutions must go, or the cover
                    // screen keeps asking for someone to replace a teacher who is standing in the room.
                    absencesCleared += staffAbsenceService.clearAbsenceFor(org, uid, r.getStaffId(), date) > 0
                            ? 1 : 0;
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("saved", saved);
            out.put("absencesOpened", absencesOpened);
            out.put("absencesCleared", absencesCleared);
            out.put("problems", problems);

            String msg = saved + " marked"
                    + (absencesOpened > 0 ? ", " + absencesOpened + " now need cover" : "");
            // PARTIAL when a row could not be read, so the UI cannot round a partial save up (1.3 D3).
            return new GenericResponse(problems.isEmpty() ? "SUCCESS" : "PARTIAL", msg, out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Fails to the documented default: a misconfigured grace must not make everyone late. */
    private int graceMinutes() {
        try {
            return settingsService.getInt(GRACE_MINUTES, GRACE_DEFAULT);
        } catch (Exception e) {
            return GRACE_DEFAULT;
        }
    }
}
