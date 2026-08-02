package com.myplus.education.controller;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.*;
import com.myplus.education.repository.*;
import com.myplus.education.service.ClashDetector;
import com.myplus.education.service.ClashDetector.Problem;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 2.1 — the timetable.
 * Design: microservices/docs/slices/edu-2.1-timetable.md
 *
 * <p>Writes are {@code ADMIN_PRIVILEGE}: a timetable decides where every teacher stands. Reads are open,
 * because everybody needs to read it.
 *
 * <p>Clash detection loads the term's grid ONCE per save and hands it to the pure
 * {@link ClashDetector} — no query per rule, and the rules stay testable without a database.
 */
@Controller
public class TimetableController {

    @Autowired private PeriodRepository periodRepository;
    @Autowired private TimetableEntryRepository timetableEntryRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private GradeRepository gradeRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TermRepository termRepository;
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

    private static Long parseLong(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return Long.valueOf(s.trim()); } catch (Exception e) { return null; }
    }

    private static Integer parseInt(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return Integer.valueOf(s.trim()); } catch (Exception e) { return null; }
    }

    private static LocalTime parseTime(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return LocalTime.parse(s.trim()); } catch (Exception e) { return null; }
    }

    // ── periods ─────────────────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/getPeriods", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getPeriods() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Period p : periodRepository.findScoped(orgId(), userId())) out.add(periodDto(p));
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/savePeriod", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse savePeriod(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String name = request.getParameter("name");
            if (!StringUtils.hasText(name)) return new GenericResponse("ERROR", "Period name is required");

            String idStr = request.getParameter("id");
            Period period;
            if (StringUtils.hasText(idStr)) {
                // Anti-IDOR: an unscoped findById here would let a caller re-time another tenant's day.
                period = periodRepository.findByIdScoped(Long.valueOf(idStr.trim()), org, uid).orElse(null);
                if (period == null) return new GenericResponse("NOT_FOUND", "Period not found");
            } else {
                period = Period.builder().userId(uid).organizationId(org).dated(LocalDateTime.now()).build();
            }
            LocalTime start = parseTime(request.getParameter("startTime"));
            LocalTime end = parseTime(request.getParameter("endTime"));
            if (start != null && end != null && !end.isAfter(start)) {
                return new GenericResponse("FAILED", "The period's end time must be after its start time");
            }
            period.setName(name.trim());
            period.setSequence(parseInt(request.getParameter("sequence")));
            period.setStartTime(start);
            period.setEndTime(end);
            // Absent parameter = a teaching period; only an explicit "false" makes it a break.
            period.setTeaching(!"false".equalsIgnoreCase(request.getParameter("teaching")));
            period.setUpdated(LocalDateTime.now());
            periodRepository.save(period);
            return new GenericResponse("SUCCESS", "Period saved");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Delete a period. Refused while it still holds lessons — deleting it would orphan them, and a
     * timetable entry with no period is invisible on every grid rather than obviously wrong.
     */
    @RequestMapping(value = "/deletePeriod", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @Transactional
    public GenericResponse deletePeriod(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long id = parseLong(request.getParameter("id"));
            if (id == null) return new GenericResponse("ERROR", "Period is required");
            Period period = periodRepository.findByIdScoped(id, org, uid).orElse(null);
            if (period == null) return new GenericResponse("NOT_FOUND", "Period not found");

            if (countPeriodUsage(org, uid, id) > 0) {
                return new GenericResponse("FAILED",
                        "This period still has lessons scheduled in it. Clear them first.");
            }
            periodRepository.delete(period);
            return new GenericResponse("SUCCESS", "Period deleted");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * How many lessons use this period, across EVERY term — a period is org-wide, so checking only the
     * term on screen would let an admin delete a period still in use by another term's timetable.
     * The term-less bucket (termId null, 1.1) is checked explicitly because it is not in the term list.
     */
    private long countPeriodUsage(Long org, Long uid, Long periodId) {
        List<Long> buckets = new ArrayList<>();
        buckets.add(null);   // the term-less timetable
        for (Term t : termRepository.findScoped(org, uid)) buckets.add(t.getId());
        long n = 0;
        for (Long termId : buckets) {
            for (TimetableEntry e : timetableEntryRepository.findByTermScoped(termId, org, uid)) {
                if (periodId.equals(e.getPeriodId())) n++;
            }
        }
        return n;
    }

    // ── the grid ────────────────────────────────────────────────────────────────────────────────

    /**
     * The timetable, by class or by teacher. Both are one query plus the shared lookup maps.
     *
     * Ungated beyond authentication: everyone in the school needs to read the timetable.
     */
    @RequestMapping(value = "/getTimetable", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getTimetable(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long termId = parseLong(request.getParameter("termId"));
            Long gradeId = parseLong(request.getParameter("gradeId"));
            Long staffId = parseLong(request.getParameter("staffId"));

            List<TimetableEntry> entries;
            if (gradeId != null) {
                entries = timetableEntryRepository.findByGradeScoped(gradeId, termId, org, uid);
            } else if (staffId != null) {
                entries = timetableEntryRepository.findByStaffScoped(staffId, termId, org, uid);
            } else {
                entries = timetableEntryRepository.findByTermScoped(termId, org, uid);
            }

            Lookups lk = lookups(org, uid);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (TimetableEntry t : entries) rows.add(entryDto(t, lk));

            List<Map<String, Object>> periods = new ArrayList<>();
            for (Period p : periodRepository.findScoped(org, uid)) periods.add(periodDto(p));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("periods", periods);
            out.put("entries", rows);
            out.put("days", List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"));
            // An empty period list is the real blocker, and it is a setup step rather than an error —
            // say so, or the grid renders blank with no explanation.
            out.put("configured", !periods.isEmpty());
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── slots (ADMIN tier) ──────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/saveTimetableEntry", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse saveTimetableEntry(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long termId = parseLong(request.getParameter("termId"));
            Long periodId = parseLong(request.getParameter("periodId"));
            Long subjectId = parseLong(request.getParameter("subjectId"));
            Long staffId = parseLong(request.getParameter("staffId"));
            String dayStr = request.getParameter("dayOfWeek");
            String room = request.getParameter("room");

            if (periodId == null) return new GenericResponse("ERROR", "Period is required");
            if (subjectId == null) return new GenericResponse("ERROR", "Subject is required");
            if (!StringUtils.hasText(dayStr)) return new GenericResponse("ERROR", "Day is required");
            DayOfWeek day;
            try {
                day = DayOfWeek.valueOf(dayStr.trim().toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return new GenericResponse("ERROR", "Unrecognised day");
            }

            Period period = periodRepository.findByIdScoped(periodId, org, uid).orElse(null);
            if (period == null) return new GenericResponse("NOT_FOUND", "Period not found");
            if (!period.isTeaching()) {
                return new GenericResponse("FAILED",
                        "\"" + period.getName() + "\" is a non-teaching period; nothing can be scheduled in it.");
            }
            Subject subject = subjectRepository.findByIdScoped(subjectId, org, uid).orElse(null);
            if (subject == null) return new GenericResponse("NOT_FOUND", "Subject not found");
            // Subject.grade is LAZY under open-in-view:false — resolved inside this transaction (1.2's lesson).
            Long subjectGradeId = subject.getGrade() == null ? null : subject.getGrade().getId();
            if (subjectGradeId == null) {
                return new GenericResponse("FAILED",
                        "\"" + subject.getName() + "\" is not attached to a class, so it cannot be timetabled.");
            }
            if (staffId != null && staffRepository.findByIdScoped(staffId, org, uid).isEmpty()) {
                return new GenericResponse("NOT_FOUND", "Teacher not found");
            }

            String idStr = request.getParameter("id");
            TimetableEntry entry;
            if (StringUtils.hasText(idStr)) {
                entry = timetableEntryRepository.findByIdScoped(Long.valueOf(idStr.trim()), org, uid).orElse(null);
                if (entry == null) return new GenericResponse("NOT_FOUND", "Timetable entry not found");
            } else {
                entry = TimetableEntry.builder().userId(uid).organizationId(org).dated(LocalDateTime.now()).build();
            }
            entry.setTermId(termId);
            entry.setDayOfWeek(day);
            entry.setPeriodId(periodId);
            entry.setSubjectId(subjectId);
            // Derived from the subject, never taken from the client — the client cannot desync what it
            // does not supply, which is the cheapest way to keep D2's copy honest.
            entry.setGradeId(subjectGradeId);
            entry.setStaffId(staffId);
            entry.setRoom(StringUtils.hasText(room) ? room.trim() : null);
            entry.setUpdated(LocalDateTime.now());

            // ONE read of the term's grid, handed to the pure validator.
            List<TimetableEntry> existing = timetableEntryRepository.findByTermScoped(termId, org, uid);
            ClashDetector.Context ctx = context(org, uid, entry, subjectGradeId, period, existing);
            List<Problem> problems = ClashDetector.check(entry, existing, ctx);
            if (ClashDetector.refuses(problems)) {
                return new GenericResponse("FAILED", ClashDetector.refusalMessage(problems));
            }
            timetableEntryRepository.save(entry);

            String warning = ClashDetector.warningMessage(problems);
            return new GenericResponse("SUCCESS",
                    warning.isBlank() ? "Lesson scheduled" : "Lesson scheduled — " + warning);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteTimetableEntry", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @Transactional
    public GenericResponse deleteTimetableEntry(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String checked = request.getParameter("checked");
            Long single = parseLong(request.getParameter("id"));
            List<String> ids = new ArrayList<>();
            if (single != null) ids.add(String.valueOf(single));
            if (StringUtils.hasText(checked)) ids.addAll(Arrays.asList(checked.split(",")));
            if (ids.isEmpty()) return new GenericResponse("SUCCESS", "Nothing to delete");

            int n = 0;
            for (String raw : ids) {
                Long id = parseLong(raw);
                if (id == null) continue;
                TimetableEntry e = timetableEntryRepository.findByIdScoped(id, org, uid).orElse(null);
                if (e == null) continue;   // not this tenant's — skip silently, as ScopedDeleter does
                timetableEntryRepository.delete(e);
                n++;
            }
            return new GenericResponse("SUCCESS", n + " lesson(s) removed");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Copy a whole term's timetable into another term.
     *
     * <p><b>Refused outright when the target term already holds entries.</b> The alternative — merging
     * into whichever slots happen to be free — produces a timetable that is half one term's plan and half
     * another's, with nothing on screen saying which row came from where. A refusal the admin can resolve
     * by clearing the target is worse to use and far easier to reason about, and this operation writes a
     * whole term in one go.
     */
    @RequestMapping(value = "/copyTimetable", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse copyTimetable(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long fromTermId = parseLong(request.getParameter("fromTermId"));
            Long toTermId = parseLong(request.getParameter("toTermId"));
            if (toTermId == null) return new GenericResponse("ERROR", "Target term is required");
            if (Objects.equals(fromTermId, toTermId)) {
                return new GenericResponse("FAILED", "The source and target terms are the same");
            }
            if (termRepository.findByIdScoped(toTermId, org, uid).isEmpty()) {
                return new GenericResponse("NOT_FOUND", "Target term not found");
            }
            if (timetableEntryRepository.countByTermScoped(toTermId, org, uid) > 0) {
                return new GenericResponse("FAILED",
                        "That term already has a timetable. Clear it before copying, so the result is not "
                                + "half one term's plan and half another's.");
            }
            List<TimetableEntry> source = timetableEntryRepository.findByTermScoped(fromTermId, org, uid);
            if (source.isEmpty()) return new GenericResponse("FAILED", "The source term has no timetable to copy");

            List<TimetableEntry> copies = new ArrayList<>();
            for (TimetableEntry s : source) {
                copies.add(TimetableEntry.builder()
                        .termId(toTermId)
                        .dayOfWeek(s.getDayOfWeek())
                        .periodId(s.getPeriodId())
                        .subjectId(s.getSubjectId())
                        .gradeId(s.getGradeId())
                        .staffId(s.getStaffId())
                        .room(s.getRoom())
                        .userId(uid).organizationId(org)
                        .dated(LocalDateTime.now()).updated(LocalDateTime.now())
                        .build());
            }
            // The target was empty and the source was already clash-free, so the copy cannot introduce a
            // clash. Saved as one batch rather than a save per lesson.
            timetableEntryRepository.saveAll(copies);
            return new GenericResponse("SUCCESS", copies.size() + " lessons copied");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** The lookup maps every grid render needs, each read once. */
    private record Lookups(Map<Long, String> subjectNames, Map<Long, String> gradeNames,
                           Map<Long, String> staffNames, Map<Long, String> periodNames) { }

    private Lookups lookups(Long org, Long uid) {
        Map<Long, String> subjects = new HashMap<>();
        for (Subject s : subjectRepository.findScoped(org, uid)) subjects.put(s.getId(), s.getName());
        Map<Long, String> grades = new HashMap<>();
        for (Grade g : gradeRepository.findScoped(org, uid)) grades.put(g.getId(), gradeLabel(g));
        Map<Long, String> staff = new HashMap<>();
        for (Staff s : staffRepository.findScoped(org, uid)) staff.put(s.getId(), s.getName());
        Map<Long, String> periods = new HashMap<>();
        for (Period p : periodRepository.findScoped(org, uid)) periods.put(p.getId(), p.getName());
        return new Lookups(subjects, grades, staff, periods);
    }

    /** Resolve the names and windows the pure validator needs, without letting it touch a repository. */
    private ClashDetector.Context context(Long org, Long uid, TimetableEntry candidate, Long subjectGradeId,
                                          Period period, List<TimetableEntry> existing) {
        Grade grade = subjectGradeId == null ? null
                : gradeRepository.findByIdScoped(subjectGradeId, org, uid).orElse(null);
        Staff staff = candidate.getStaffId() == null ? null
                : staffRepository.findByIdScoped(candidate.getStaffId(), org, uid).orElse(null);

        String otherClassForStaff = null, otherSubjectForClass = null, otherClassInRoom = null;
        Lookups lk = lookups(org, uid);
        for (TimetableEntry other : existing) {
            if (candidate.getId() != null && candidate.getId().equals(other.getId())) continue;
            if (!Objects.equals(candidate.getTermId(), other.getTermId())) continue;
            if (candidate.getDayOfWeek() != other.getDayOfWeek()) continue;
            if (!Objects.equals(candidate.getPeriodId(), other.getPeriodId())) continue;

            if (candidate.getStaffId() != null && Objects.equals(candidate.getStaffId(), other.getStaffId())) {
                otherClassForStaff = lk.gradeNames().get(other.getGradeId());
            }
            if (Objects.equals(candidate.getGradeId(), other.getGradeId())) {
                otherSubjectForClass = lk.subjectNames().get(other.getSubjectId());
            }
            if (candidate.getRoom() != null && candidate.getRoom().equalsIgnoreCase(other.getRoom())) {
                otherClassInRoom = lk.gradeNames().get(other.getGradeId());
            }
        }
        return new ClashDetector.Context(subjectGradeId,
                grade == null ? null : grade.getTimeFrom(), grade == null ? null : grade.getTimeTo(),
                staff == null ? null : staff.getTimeIn(), staff == null ? null : staff.getTimeOut(),
                period.getStartTime(), period.getEndTime(),
                otherClassInRoom, otherClassForStaff, otherSubjectForClass);
    }

    private Map<String, Object> periodDto(Period p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("sequence", p.getSequence());
        m.put("startTime", p.getStartTime() == null ? null : p.getStartTime().toString());
        m.put("endTime", p.getEndTime() == null ? null : p.getEndTime().toString());
        m.put("teaching", p.isTeaching());
        return m;
    }

    private Map<String, Object> entryDto(TimetableEntry t, Lookups lk) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("termId", t.getTermId());
        m.put("dayOfWeek", t.getDayOfWeek() == null ? null : t.getDayOfWeek().name());
        m.put("periodId", t.getPeriodId());
        m.put("periodName", lk.periodNames().get(t.getPeriodId()));
        m.put("subjectId", t.getSubjectId());
        m.put("subjectName", lk.subjectNames().get(t.getSubjectId()));
        m.put("gradeId", t.getGradeId());
        m.put("gradeName", lk.gradeNames().get(t.getGradeId()));
        m.put("staffId", t.getStaffId());
        m.put("staffName", lk.staffNames().get(t.getStaffId()));
        m.put("room", t.getRoom());
        return m;
    }

    private String gradeLabel(Grade g) {
        String n = g.getName() == null ? "Class" : g.getName();
        return g.getSection() == null || g.getSection().isBlank() ? n : n + " " + g.getSection();
    }
}
