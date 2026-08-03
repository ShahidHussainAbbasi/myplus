package com.myplus.education.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import com.myplus.education.entity.*;
import com.myplus.education.repository.*;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.service.GradingService;
import com.myplus.education.service.HomeworkRules;
import com.myplus.education.service.StudentVisibilityService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 2.4 — homework: set, submit, mark.
 * Design: microservices/docs/slices/edu-2.4-homework.md
 *
 * <p><b>WRITE tier, not ADMIN.</b> Setting and grading homework is teacher work, exactly as marks entry is
 * (1.3 D6). The ADMIN tier is for policy and money — who exists, what parents owe, who teaches whom.
 *
 * <p><b>Two things this deliberately does NOT do.</b> It does not pre-seed submission rows for a class
 * (D2 — that would assert facts that are not yet true, and silently miss anyone who joins later), and it
 * does not contribute to the report card (D4 — 1.5's term aggregate is a published number, and adding a
 * source would change its meaning with nothing showing it had changed).
 */
@Controller
public class HomeworkController {

    @Autowired private HomeworkRepository homeworkRepository;
    @Autowired private HomeworkSubmissionRepository submissionRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private GradeRepository gradeRepository;
    @Autowired private StudentVisibilityService studentVisibilityService;
    @Autowired private GradingService gradingService;
    @Autowired private EduAuditService auditService;
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

    private static LocalDate parseDate(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return null; }
    }

    /** The JSON body of a mark-sheet save — a row list, the same shape as {@code saveMarksBulk}. */
    public static class BulkSubmissionRequest {
        private Long homeworkId;
        private List<Row> rows;

        public Long getHomeworkId() { return homeworkId; }
        public void setHomeworkId(Long v) { this.homeworkId = v; }
        public List<Row> getRows() { return rows; }
        public void setRows(List<Row> v) { this.rows = v; }

        public static class Row {
            private String enrollNo;
            private String state;
            private String submittedOn;
            private Integer marksObtained;
            private String feedback;
            public String getEnrollNo() { return enrollNo; }
            public void setEnrollNo(String v) { this.enrollNo = v; }
            public String getState() { return state; }
            public void setState(String v) { this.state = v; }
            public String getSubmittedOn() { return submittedOn; }
            public void setSubmittedOn(String v) { this.submittedOn = v; }
            public Integer getMarksObtained() { return marksObtained; }
            public void setMarksObtained(Integer v) { this.marksObtained = v; }
            public String getFeedback() { return feedback; }
            public void setFeedback(String v) { this.feedback = v; }
        }
    }

    // ── the tasks ───────────────────────────────────────────────────────────────────────────────

    /**
     * Homework set, optionally filtered by subject, with a completion count each.
     *
     * <p>Counts come from ONE query over every task on the page rather than a query per task — the
     * batch-not-per-row discipline from 1.1, 1.5 and finding D.
     */
    @RequestMapping(value = "/getHomework", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getHomework(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long subjectId = parseLong(request.getParameter("subjectId"));

            List<Homework> tasks = subjectId != null
                    ? homeworkRepository.findBySubjectScoped(subjectId, org, uid)
                    : homeworkRepository.findScoped(org, uid);

            Map<Long, Integer> recorded = new HashMap<>();
            if (!tasks.isEmpty()) {
                List<Long> ids = new ArrayList<>();
                for (Homework h : tasks) ids.add(h.getId());
                for (HomeworkSubmission s : submissionRepository.findByHomeworkIdsScoped(ids, org, uid)) {
                    recorded.merge(s.getHomeworkId(), 1, Integer::sum);
                }
            }

            Map<Long, String> subjectNames = new HashMap<>();
            Map<Long, Long> gradeOfSubject = new HashMap<>();
            for (Subject s : subjectRepository.findScoped(org, uid)) {
                subjectNames.put(s.getId(), s.getName());
                gradeOfSubject.put(s.getId(), s.getGrade() == null ? null : s.getGrade().getId());
            }
            Map<Long, String> gradeNames = new HashMap<>();
            for (Grade g : gradeRepository.findScoped(org, uid)) gradeNames.put(g.getId(), gradeLabel(g));

            LocalDate today = LocalDate.now();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Homework h : tasks) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", h.getId());
                m.put("subjectId", h.getSubjectId());
                m.put("subjectName", subjectNames.get(h.getSubjectId()));
                // The class is DERIVED through subject → grade (1.2 D2), never stored on the task.
                m.put("gradeName", gradeNames.get(gradeOfSubject.get(h.getSubjectId())));
                m.put("title", h.getTitle());
                m.put("instructions", h.getInstructions());
                m.put("setOn", h.getSetOn() == null ? null : h.getSetOn().toString());
                m.put("dueOn", h.getDueOn() == null ? null : h.getDueOn().toString());
                m.put("maxMarks", h.getMaxMarks());
                m.put("recorded", recorded.getOrDefault(h.getId(), 0));
                m.put("pastDue", h.getDueOn() != null && today.isAfter(h.getDueOn()));
                out.add(m);
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/saveHomework", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @Transactional
    public GenericResponse saveHomework(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String title = request.getParameter("title");
            Long subjectId = parseLong(request.getParameter("subjectId"));
            if (!StringUtils.hasText(title)) return new GenericResponse("ERROR", "A title is required");
            if (subjectId == null) return new GenericResponse("ERROR", "Subject is required");

            // Scoped: an unchecked lookup would let a caller set homework against another tenant's subject.
            Subject subject = subjectRepository.findByIdScoped(subjectId, org, uid).orElse(null);
            if (subject == null) return new GenericResponse("NOT_FOUND", "Subject not found");

            LocalDate setOn = parseDate(request.getParameter("setOn"));
            LocalDate dueOn = parseDate(request.getParameter("dueOn"));
            if (setOn != null && dueOn != null && dueOn.isBefore(setOn)) {
                return new GenericResponse("FAILED", "The due date is before the date it was set");
            }
            Integer maxMarks = parseInt(request.getParameter("maxMarks"));
            if (maxMarks != null && maxMarks <= 0) {
                return new GenericResponse("FAILED", "Maximum marks must be greater than zero");
            }

            String idStr = request.getParameter("id");
            Homework hw;
            if (StringUtils.hasText(idStr)) {
                hw = homeworkRepository.findByIdScoped(Long.valueOf(idStr.trim()), org, uid).orElse(null);
                if (hw == null) return new GenericResponse("NOT_FOUND", "Homework not found");
            } else {
                hw = Homework.builder().userId(uid).organizationId(org).dated(LocalDateTime.now()).build();
            }
            hw.setSubjectId(subjectId);
            hw.setTermId(parseLong(request.getParameter("termId")));
            hw.setTitle(title.trim());
            hw.setInstructions(StringUtils.hasText(request.getParameter("instructions"))
                    ? request.getParameter("instructions").trim() : null);
            hw.setSetOn(setOn != null ? setOn : LocalDate.now());
            hw.setDueOn(dueOn);
            hw.setMaxMarks(maxMarks);
            hw.setUpdated(LocalDateTime.now());
            homeworkRepository.save(hw);

            // D2: NO submission rows are created here. A class of 40 gets zero rows until something is
            // actually recorded — pre-seeding would assert 40 facts that are not yet true.
            return new GenericResponse("SUCCESS", "Homework saved");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Refused once anything is graded — a mark is a judgement of a child's work, not a draft. */
    @RequestMapping(value = "/deleteHomework", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @Transactional
    public GenericResponse deleteHomework(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long id = parseLong(request.getParameter("id"));
            if (id == null) return new GenericResponse("ERROR", "Homework is required");
            Homework hw = homeworkRepository.findByIdScoped(id, org, uid).orElse(null);
            if (hw == null) return new GenericResponse("NOT_FOUND", "Homework not found");

            List<HomeworkSubmission> subs = submissionRepository.findByHomeworkScoped(id, org, uid);
            if (!HomeworkRules.canDelete(subs)) {
                return new GenericResponse("FAILED",
                        "This homework has graded work against it and cannot be deleted.");
            }
            for (HomeworkSubmission s : subs) submissionRepository.delete(s);
            homeworkRepository.delete(hw);
            return new GenericResponse("SUCCESS", "Homework deleted");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── the mark sheet ──────────────────────────────────────────────────────────────────────────

    /**
     * The roster for the task's class, with whatever has been recorded against each student.
     *
     * <p>D2 in practice: the roster comes from {@code StudentVisibilityService} and the (lazily created)
     * submissions are joined onto it. A student with no row has nothing recorded — including one who
     * joined the class after the homework was set, who therefore appears correctly rather than being
     * silently absent from a pre-seeded list.
     */
    @RequestMapping(value = "/getHomeworkSheet", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getHomeworkSheet(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long homeworkId = parseLong(request.getParameter("homeworkId"));
            if (homeworkId == null) return new GenericResponse("ERROR", "Homework is required");

            Homework hw = homeworkRepository.findByIdScoped(homeworkId, org, uid).orElse(null);
            if (hw == null) return new GenericResponse("NOT_FOUND", "Homework not found");
            Subject subject = subjectRepository.findByIdScoped(hw.getSubjectId(), org, uid).orElse(null);
            Long gradeId = subject == null || subject.getGrade() == null ? null : subject.getGrade().getId();

            Map<String, HomeworkSubmission> byStudent = new HashMap<>();
            for (HomeworkSubmission s : submissionRepository.findByHomeworkScoped(homeworkId, org, uid)) {
                byStudent.put(s.getStudentEnrollNo(), s);
            }

            // Slice 1.4: the grading scale is read ONCE per sheet, not per student.
            List<GradeBand> scale = gradingService.scale(org, uid);
            LocalDate today = LocalDate.now();

            List<Map<String, Object>> rows = new ArrayList<>();
            for (Student st : studentVisibilityService.visibleStudents(org, uid)) {
                if (st.getEnrollNo() == null || st.getEnrollNo().isBlank()) continue;
                if (gradeId != null && !gradeId.equals(st.getGradeId())) continue;

                HomeworkSubmission s = byStudent.get(st.getEnrollNo());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("enrollNo", st.getEnrollNo());
                m.put("name", st.getName());
                m.put("state", s == null ? null : s.getState().name());
                m.put("submittedOn", s == null || s.getSubmittedOn() == null ? null
                        : s.getSubmittedOn().toString());
                m.put("marksObtained", s == null ? null : s.getMarksObtained());
                m.put("feedback", s == null ? null : s.getFeedback());
                // Both DERIVED (D5): extending the deadline changes them, which a stored flag could not do.
                m.put("late", s != null && HomeworkRules.isLate(s.getSubmittedOn(), hw.getDueOn()));
                m.put("overdue", HomeworkRules.isOverdueUnrecorded(
                        s == null ? null : s.getState(), hw.getDueOn(), today));
                // Reuses 1.4's scale — the same percentage and band the marksheet would show.
                Double percent = s == null ? null
                        : gradingService.percentOf(s.getMarksObtained(), hw.getMaxMarks());
                m.put("percent", percent);
                GradeBand band = gradingService.bandFor(scale, percent);
                m.put("grade", band == null ? null : band.getName());
                rows.add(m);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("homeworkId", hw.getId());
            out.put("title", hw.getTitle());
            out.put("instructions", hw.getInstructions());
            out.put("dueOn", hw.getDueOn() == null ? null : hw.getDueOn().toString());
            out.put("maxMarks", hw.getMaxMarks());
            out.put("subjectName", subject == null ? null : subject.getName());
            out.put("rows", rows);
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Save the mark sheet.
     *
     * <p>Per-row partial success (1.3 D3): valid rows save and invalid rows are named, with the status
     * {@code PARTIAL} rather than {@code SUCCESS} so the UI cannot round a partial write up. A row with no
     * state is left alone — "nothing recorded yet" is a legitimate answer, not an error, and forcing a
     * value would push teachers into recording judgements they have not made.
     */
    @RequestMapping(value = "/saveSubmissionBulk", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @Transactional
    public GenericResponse saveSubmissionBulk(@RequestBody BulkSubmissionRequest req) {
        try {
            if (req == null || req.getHomeworkId() == null) {
                return new GenericResponse("ERROR", "Homework is required");
            }
            if (req.getRows() == null || req.getRows().isEmpty()) {
                return new GenericResponse("INVALID", "Nothing to save");
            }
            Long org = orgId(), uid = userId();
            Homework hw = homeworkRepository.findByIdScoped(req.getHomeworkId(), org, uid).orElse(null);
            if (hw == null) return new GenericResponse("NOT_FOUND", "Homework not found");

            // Roster and existing rows read ONCE for the batch, never per row.
            Set<String> visible = new HashSet<>();
            for (Student st : studentVisibilityService.visibleStudents(org, uid)) {
                if (st.getEnrollNo() != null) visible.add(st.getEnrollNo());
            }
            Map<String, HomeworkSubmission> existing = new HashMap<>();
            for (HomeworkSubmission s : submissionRepository.findByHomeworkScoped(hw.getId(), org, uid)) {
                existing.put(s.getStudentEnrollNo(), s);
            }

            int saved = 0, cleared = 0;
            List<String> problems = new ArrayList<>();

            for (BulkSubmissionRequest.Row r : req.getRows()) {
                if (r == null || !StringUtils.hasText(r.getEnrollNo())) continue;
                String enrollNo = r.getEnrollNo().trim();
                // Out of the caller's branch: skipped SILENTLY, as 1.3 D3 does — an error would confirm
                // that another campus's student exists.
                if (!visible.contains(enrollNo)) continue;

                HomeworkSubmission row = existing.get(enrollNo);

                // A blank state means "nothing recorded". If a row exists, that is a deliberate clear.
                if (!StringUtils.hasText(r.getState())) {
                    if (row != null) { submissionRepository.delete(row); cleared++; }
                    continue;
                }

                SubmissionState state;
                try {
                    state = SubmissionState.valueOf(r.getState().trim().toUpperCase(Locale.ROOT));
                } catch (Exception e) {
                    problems.add(enrollNo + ": unrecognised state");
                    continue;
                }
                String marksProblem = HomeworkRules.validateMarks(r.getMarksObtained(), hw.getMaxMarks());
                if (marksProblem != null) {
                    problems.add(enrollNo + ": " + marksProblem);
                    continue;
                }

                Integer previousMarks = row == null ? null : row.getMarksObtained();
                if (row == null) {
                    row = HomeworkSubmission.builder()
                            .homeworkId(hw.getId()).studentEnrollNo(enrollNo)
                            .userId(uid).organizationId(org).dated(LocalDateTime.now())
                            .build();
                }
                row.setState(state);
                row.setSubmittedOn(parseDate(r.getSubmittedOn()));
                row.setMarksObtained(r.getMarksObtained());
                row.setFeedback(StringUtils.hasText(r.getFeedback()) ? r.getFeedback().trim() : null);
                row.setUpdated(LocalDateTime.now());
                submissionRepository.save(row);
                saved++;

                // A changed grade is contested data, exactly as a changed exam mark is (1.3 D5).
                if (!Objects.equals(previousMarks, r.getMarksObtained())) {
                    auditService.record("HOMEWORK_MARK_CHANGED", "HomeworkSubmission",
                            hw.getId() + ":" + enrollNo,
                            "from=" + previousMarks + " to=" + r.getMarksObtained()
                                    + " of " + hw.getMaxMarks());
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("saved", saved);
            out.put("cleared", cleared);
            out.put("problems", problems);
            String msg = saved + " recorded" + (cleared > 0 ? ", " + cleared + " cleared" : "");
            return new GenericResponse(problems.isEmpty() ? "SUCCESS" : "PARTIAL", msg, out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    private String gradeLabel(Grade g) {
        String n = g.getName() == null ? "Class" : g.getName();
        return g.getSection() == null || g.getSection().isBlank() ? n : n + " " + g.getSection();
    }
}
