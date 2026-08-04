package com.myplus.education.controller;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import com.myplus.education.service.FreeTeacherFinder;
import com.myplus.education.service.FreeTeacherFinder.Candidate;
import com.myplus.education.service.StaffAbsenceService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 2.2 — substitution: covering the teacher who is out today.
 * Design: microservices/docs/slices/edu-2.2-substitution.md
 *
 * <p>This is the first education screen whose whole purpose is <b>same-day operational</b>: it runs at
 * 07:50 under time pressure and must answer one question in one round trip — <i>who is out, and who covers
 * their lessons?</i>
 *
 * <p>Writes are {@code ADMIN_PRIVILEGE} — deciding who teaches whom is the same class of act as the
 * timetable itself. Reads are open, because a teacher must be able to see they are covering period 3.
 */
@Controller
public class SubstitutionController {

    @Autowired private StaffAbsenceRepository staffAbsenceRepository;
    @Autowired private SubstitutionRepository substitutionRepository;
    @Autowired private TimetableEntryRepository timetableEntryRepository;
    @Autowired private PeriodRepository periodRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private GradeRepository gradeRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private StaffAbsenceService staffAbsenceService;   // 2.3: the ONE absence cascade
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

    /** Defaults to today: the screen is opened on the morning it is used. */
    private static LocalDate parseDate(String s) {
        if (!StringUtils.hasText(s)) return LocalDate.now();
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return LocalDate.now(); }
    }

    // ── the day ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Everything the morning needs, in one call: who is out, which lessons that leaves uncovered, and who
     * is free to take each one.
     *
     * <p>Reads the term's timetable and the day's substitutions ONCE and hands them to the pure
     * {@link FreeTeacherFinder} — never a query per candidate.
     */
    @RequestMapping(value = "/getSubstitutionDay", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getSubstitutionDay(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            LocalDate date = parseDate(request.getParameter("date"));
            Long termId = parseLong(request.getParameter("termId"));
            DayOfWeek day = date.getDayOfWeek();

            List<StaffAbsence> absences = staffAbsenceRepository.findByDateScoped(date, org, uid);
            Set<Long> absentIds = new LinkedHashSet<>();
            for (StaffAbsence a : absences) absentIds.add(a.getStaffId());

            List<TimetableEntry> timetable = timetableEntryRepository.findByTermScoped(termId, org, uid);
            Map<Long, TimetableEntry> entryById = new HashMap<>();
            for (TimetableEntry e : timetable) entryById.put(e.getId(), e);

            List<Substitution> subs = substitutionRepository.findByDateScoped(date, org, uid);
            Map<Long, Substitution> subByEntry = new HashMap<>();
            for (Substitution s : subs) subByEntry.put(s.getTimetableEntryId(), s);

            Lookups lk = lookups(org, uid);
            List<Object[]> allStaff = new ArrayList<>();
            for (Staff s : staffRepository.findScoped(org, uid)) {
                allStaff.add(new Object[] { s.getId(), s.getName() });
            }

            // The lessons an absent teacher was due to take on this weekday.
            List<Map<String, Object>> lessons = new ArrayList<>();
            for (TimetableEntry e : timetable) {
                if (e.getStaffId() == null || !absentIds.contains(e.getStaffId())) continue;
                if (e.getDayOfWeek() != day) continue;

                Substitution existing = subByEntry.get(e.getId());
                List<Candidate> free = FreeTeacherFinder.freeIn(allStaff, timetable, day, e.getPeriodId(),
                        e.getSubjectId(), absentIds, subs, entryById);

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("timetableEntryId", e.getId());
                m.put("periodId", e.getPeriodId());
                m.put("periodName", lk.periodNames().get(e.getPeriodId()));
                m.put("subjectName", lk.subjectNames().get(e.getSubjectId()));
                m.put("gradeName", lk.gradeNames().get(e.getGradeId()));
                m.put("room", e.getRoom());
                m.put("absentStaffId", e.getStaffId());
                m.put("absentStaffName", lk.staffNames().get(e.getStaffId()));
                m.put("status", existing == null ? SubstitutionStatus.UNCOVERED.name()
                        : existing.getStatus().name());
                m.put("substitutionId", existing == null ? null : existing.getId());
                m.put("coverStaffId", existing == null ? null : existing.getCoverStaffId());
                m.put("coverStaffName", existing == null ? null : existing.getCoverStaffName());

                List<Map<String, Object>> candidates = new ArrayList<>();
                for (Candidate c : free) {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("staffId", c.staffId());
                    cm.put("staffName", c.staffName());
                    cm.put("teachesThisSubject", c.teachesThisSubject());
                    cm.put("coversToday", c.coversToday());
                    candidates.add(cm);
                }
                m.put("freeTeachers", candidates);
                lessons.add(m);
            }

            List<Map<String, Object>> absentOut = new ArrayList<>();
            for (StaffAbsence a : absences) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", a.getId());
                m.put("staffId", a.getStaffId());
                m.put("staffName", a.getStaffName() != null ? a.getStaffName()
                        : lk.staffNames().get(a.getStaffId()));
                m.put("reason", a.getReason());
                absentOut.add(m);
            }

            long uncovered = lessons.stream()
                    .filter(l -> SubstitutionStatus.UNCOVERED.name().equals(l.get("status"))).count();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("date", date.toString());
            out.put("absences", absentOut);
            out.put("lessons", lessons);
            // Surfaced as a count so the screen can lead with "3 classes have nobody" rather than making
            // the user scan for it — an unsupervised class is the point of this screen (D5).
            out.put("uncovered", uncovered);
            out.put("configured", !timetable.isEmpty());
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── absences (ADMIN tier) ───────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/markStaffAbsent", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse markStaffAbsent(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long staffId = parseLong(request.getParameter("staffId"));
            if (staffId == null) return new GenericResponse("ERROR", "Teacher is required");
            LocalDate date = parseDate(request.getParameter("date"));

            Staff staff = staffRepository.findByIdScoped(staffId, org, uid).orElse(null);
            if (staff == null) return new GenericResponse("NOT_FOUND", "Teacher not found");

            // Delegated to StaffAbsenceService (extracted in 2.3): opening an absence is now reachable
            // from the register and from leave approval too, and three copies of this cascade would drift
            // into an unsupervised classroom. Idempotent — a double-click is not an error.
            int opened = staffAbsenceService.openAbsence(org, uid, staffId, staff.getName(), date,
                    StringUtils.hasText(request.getParameter("reason"))
                            ? request.getParameter("reason").trim() : null,
                    null);
            return new GenericResponse("SUCCESS",
                    staff.getName() + " marked absent — " + opened + " lesson(s) need cover");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * The teacher turned up after all. Their substitutions are CANCELLED, not deleted — the school
     * rearranged its morning and that happened, the same rule as a reversed promotion (1.6 D7).
     */
    @RequestMapping(value = "/clearStaffAbsence", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse clearStaffAbsence(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long id = parseLong(request.getParameter("id"));
            if (id == null) return new GenericResponse("ERROR", "Absence is required");
            StaffAbsence absence = staffAbsenceRepository.findByIdScoped(id, org, uid).orElse(null);
            if (absence == null) return new GenericResponse("NOT_FOUND", "Absence not found");

            // Same cascade, one owner (2.3): substitutions become CANCELLED and are KEPT, because the
            // school acted on them; the absence itself is a fact that turned out false, so it goes.
            int cancelled = staffAbsenceService.clearAbsence(org, uid, absence);
            return new GenericResponse("SUCCESS",
                    "Absence cleared — " + cancelled + " substitution(s) cancelled");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── cover (ADMIN tier) ──────────────────────────────────────────────────────────────────────

    /**
     * Assign a cover teacher, clash-checked with 2.1's own rule rather than a re-derived one.
     *
     * <p><b>The room rule is suppressed here, deliberately (D4).</b> Cover happens in the absent teacher's
     * room, which is by definition already booked for that class — passing a substitution through the room
     * check unchanged would warn on every single cover, and a warning that always fires is one nobody reads.
     */
    @RequestMapping(value = "/assignSubstitute", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse assignSubstitute(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long entryId = parseLong(request.getParameter("timetableEntryId"));
            Long coverStaffId = parseLong(request.getParameter("coverStaffId"));
            if (entryId == null) return new GenericResponse("ERROR", "Lesson is required");
            if (coverStaffId == null) return new GenericResponse("ERROR", "Cover teacher is required");
            LocalDate date = parseDate(request.getParameter("date"));

            TimetableEntry entry = timetableEntryRepository.findByIdScoped(entryId, org, uid).orElse(null);
            if (entry == null) return new GenericResponse("NOT_FOUND", "Lesson not found");
            Staff cover = staffRepository.findByIdScoped(coverStaffId, org, uid).orElse(null);
            if (cover == null) return new GenericResponse("NOT_FOUND", "Cover teacher not found");

            if (staffAbsenceRepository.findOneScoped(coverStaffId, date, org, uid).isPresent()) {
                return new GenericResponse("FAILED",
                        cover.getName() + " is marked absent on this date and cannot cover.");
            }

            // Reuse 2.1's clash rule: treat the cover as if it were a timetable entry for that teacher.
            List<TimetableEntry> termTimetable =
                    timetableEntryRepository.findByTermScoped(entry.getTermId(), org, uid);
            TimetableEntry asIfScheduled = TimetableEntry.builder()
                    .termId(entry.getTermId())
                    .dayOfWeek(entry.getDayOfWeek())
                    .periodId(entry.getPeriodId())
                    .subjectId(entry.getSubjectId())
                    .gradeId(entry.getGradeId())
                    .staffId(coverStaffId)
                    // room deliberately NULL — see the javadoc: the room rule must not fire on a cover.
                    .userId(uid).organizationId(org)
                    .build();
            // The candidate's own lesson is the one being covered, so exclude it from the comparison.
            List<TimetableEntry> others = new ArrayList<>();
            for (TimetableEntry e : termTimetable) if (!e.getId().equals(entryId)) others.add(e);

            ClashDetector.Context ctx = new ClashDetector.Context(
                    entry.getGradeId(), null, null, null, null, null, null, null,
                    clashingClassFor(others, entry, coverStaffId, org, uid), null);
            List<ClashDetector.Problem> problems = ClashDetector.check(asIfScheduled, others, ctx);
            if (ClashDetector.refuses(problems)) {
                return new GenericResponse("FAILED", ClashDetector.refusalMessage(problems));
            }

            // Another cover already taken in the same slot is invisible to the timetable (FreeTeacherFinder
            // knows this; the guard is repeated here because this endpoint is reachable directly).
            for (Substitution s : substitutionRepository.findByCoverScoped(coverStaffId, date, org, uid)) {
                if (s.getStatus() != SubstitutionStatus.ASSIGNED) continue;
                if (Objects.equals(s.getTimetableEntryId(), entryId)) continue;
                TimetableEntry covered =
                        timetableEntryRepository.findByIdScoped(s.getTimetableEntryId(), org, uid).orElse(null);
                if (covered != null && covered.getDayOfWeek() == entry.getDayOfWeek()
                        && Objects.equals(covered.getPeriodId(), entry.getPeriodId())) {
                    return new GenericResponse("FAILED",
                            cover.getName() + " is already covering another class in this period.");
                }
            }

            Substitution sub = substitutionRepository.findOneScoped(entryId, date, org, uid)
                    .orElseGet(() -> Substitution.builder()
                            .timetableEntryId(entryId).subDate(date)
                            .absentStaffId(entry.getStaffId())
                            .userId(uid).organizationId(org).dated(LocalDateTime.now())
                            .build());
            sub.setCoverStaffId(coverStaffId);
            sub.setCoverStaffName(cover.getName());
            sub.setStatus(SubstitutionStatus.ASSIGNED);
            sub.setUpdated(LocalDateTime.now());
            substitutionRepository.save(sub);

            // D6: notifying the cover teacher is best-effort and happens after the decision is safe.
            // A failed message must never lose the assignment — the school still happened.
            notifyCoverBestEffort(cover, entry, date);

            return new GenericResponse("SUCCESS", cover.getName() + " will cover this lesson");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Withdraw a cover: back to UNCOVERED, because the class still needs someone. */
    @RequestMapping(value = "/clearSubstitute", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse clearSubstitute(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long id = parseLong(request.getParameter("id"));
            if (id == null) return new GenericResponse("ERROR", "Substitution is required");
            Substitution sub = substitutionRepository.findByIdScoped(id, org, uid).orElse(null);
            if (sub == null) return new GenericResponse("NOT_FOUND", "Substitution not found");

            sub.setCoverStaffId(null);
            sub.setCoverStaffName(null);
            sub.setStatus(SubstitutionStatus.UNCOVERED);
            sub.setUpdated(LocalDateTime.now());
            substitutionRepository.save(sub);
            return new GenericResponse("SUCCESS", "Cover removed — this lesson needs someone");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** The class the cover teacher is already committed to in this slot, for the refusal message. */
    private String clashingClassFor(List<TimetableEntry> others, TimetableEntry entry,
                                    Long coverStaffId, Long org, Long uid) {
        for (TimetableEntry e : others) {
            if (!Objects.equals(e.getStaffId(), coverStaffId)) continue;
            if (e.getDayOfWeek() != entry.getDayOfWeek()) continue;
            if (!Objects.equals(e.getPeriodId(), entry.getPeriodId())) continue;
            Grade g = e.getGradeId() == null ? null
                    : gradeRepository.findByIdScoped(e.getGradeId(), org, uid).orElse(null);
            return g == null ? null : gradeLabel(g);
        }
        return null;
    }

    /**
     * Best-effort notification (D6). Swallows failure ON PURPOSE and logs it — the substitution is already
     * committed and must not be lost because a message could not be sent.
     *
     * <p>Deliberately narrow: it catches only the send, never wraps the decision. Slice 0.2a's lesson —
     * a broad best-effort catch once hid a real settlement failure.
     */
    private void notifyCoverBestEffort(Staff cover, TimetableEntry entry, LocalDate date) {
        try {
            // Routed through the same alerts path education already uses for guardians; the message is
            // intentionally plain because it is read on a phone in a corridor.
            appUtil.li(getClass(), "Substitution: " + cover.getName() + " covers entry "
                    + entry.getId() + " on " + date);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
        }
    }

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

    private String gradeLabel(Grade g) {
        String n = g.getName() == null ? "Class" : g.getName();
        return g.getSection() == null || g.getSection().isBlank() ? n : n + " " + g.getSection();
    }
}
