package com.myplus.education.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.myplus.education.entity.Exam;
import com.myplus.education.entity.ExamPaper;
import com.myplus.education.entity.ExamStatus;
import com.myplus.education.entity.Subject;
import com.myplus.education.entity.Term;
import com.myplus.education.repository.ExamPaperRepository;
import com.myplus.education.repository.ExamRepository;
import com.myplus.education.repository.SubjectRepository;
import com.myplus.education.repository.TermRepository;
import com.myplus.education.service.ExamLockGuard;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 1.2 — examinations.
 * Design: microservices/docs/slices/edu-1.2-examinations.md
 *
 * Privilege tier (D-3): defining an exam decides what marks are possible, so writes are
 * ADMIN_PRIVILEGE and deletes DELETE_PRIVILEGE. Reads stay open — every screen showing a result needs
 * to name the exam it came from.
 */
@Controller
public class ExamController {

    private static final DateTimeFormatter UI_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    @Autowired private ExamRepository examRepository;
    @Autowired private ExamPaperRepository examPaperRepository;
    @Autowired private TermRepository termRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private RequestUtil requestUtil;
    @Autowired private AppUtil appUtil;
    @Autowired private com.myplus.education.service.EduAuditService auditService;   // slice 1.3 (D5)

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    /** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    private static LocalDate parseDate(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return LocalDate.parse(s.trim(), UI_DATE); } catch (Exception e) { return null; }
    }

    private static LocalTime parseTime(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return LocalTime.parse(s.trim()); } catch (Exception e) { return null; }
    }

    private static Integer parseInt(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return Integer.valueOf(s.trim()); } catch (Exception e) { return null; }
    }

    private static String fmt(LocalDate d) { return d == null ? null : d.format(UI_DATE); }

    // ── DTO shaping ─────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> examDto(Exam e, List<ExamPaper> papers, Map<Long, Subject> subjects) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("type", e.getType());
        m.put("termId", e.getTermId());
        m.put("weightPercent", e.getWeightPercent());
        m.put("status", e.getStatus() == null ? null : e.getStatus().name());
        // The UI greys out the restating fields rather than letting the user discover the lock on save.
        m.put("locked", ExamLockGuard.isLocked(e.getStatus()));
        List<Map<String, Object>> ps = new ArrayList<>();
        if (papers != null) for (ExamPaper p : papers) ps.add(paperDto(p, subjects));
        m.put("papers", ps);
        return m;
    }

    private Map<String, Object> paperDto(ExamPaper p, Map<Long, Subject> subjects) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("examId", p.getExamId());
        m.put("subjectId", p.getSubjectId());
        m.put("maxMarks", p.getMaxMarks());
        m.put("passMarks", p.getPassMarks());
        m.put("examDateStr", fmt(p.getExamDate()));
        m.put("timeFrom", p.getTimeFrom() == null ? null : p.getTimeFrom().toString());
        m.put("timeTo", p.getTimeTo() == null ? null : p.getTimeTo().toString());
        // Derived (D2): the paper stores no class — it is reached through the subject.
        Subject s = subjects == null ? null : subjects.get(p.getSubjectId());
        m.put("subjectName", s == null ? null : s.getName());
        m.put("gradeId", s == null || s.getGrade() == null ? null : s.getGrade().getId());
        m.put("gradeName", s == null || s.getGrade() == null ? null : s.getGrade().getName());
        return m;
    }

    /**
     * Subjects by id, loaded ONCE per request. Building this map beats resolving each paper's subject
     * individually — a 12-paper datesheet would otherwise issue 12 extra queries, plus one more each for
     * the lazy Grade.
     */
    private Map<Long, Subject> subjectIndex(Long org, Long uid) {
        Map<Long, Subject> byId = new LinkedHashMap<>();
        for (Subject s : subjectRepository.findScoped(org, uid)) {
            if (s.getGrade() != null) s.getGrade().getName();   // touch inside the tx: Grade is LAZY
            byId.put(s.getId(), s);
        }
        return byId;
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Every exam for the tenant with its papers nested.
     * {@code @Transactional(readOnly = true)} because {@code Subject.grade} is LAZY and
     * {@code open-in-view} is false — without it the derived class would throw on access.
     */
    @RequestMapping(value = "/getExams", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getExams(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Map<Long, Subject> subjects = subjectIndex(org, uid);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Exam e : examRepository.findScoped(org, uid)) {
                out.add(examDto(e, examPaperRepository.findByExamScoped(e.getId(), org, uid), subjects));
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * The datesheet: papers in date order, optionally for one class.
     * The class filter is applied through the SUBJECT (D2) — there is no gradeId on a paper to filter on.
     */
    @RequestMapping(value = "/getDatesheet", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getDatesheet(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long gradeId = StringUtils.hasText(request.getParameter("gradeId"))
                    ? Long.valueOf(request.getParameter("gradeId").trim()) : null;
            String examIdStr = request.getParameter("examId");

            Map<Long, Subject> subjects = subjectIndex(org, uid);
            List<ExamPaper> papers = StringUtils.hasText(examIdStr)
                    ? examPaperRepository.findByExamScoped(Long.valueOf(examIdStr.trim()), org, uid)
                    : examPaperRepository.findScoped(org, uid);

            List<Map<String, Object>> out = new ArrayList<>();
            for (ExamPaper p : papers) {
                Subject s = subjects.get(p.getSubjectId());
                if (gradeId != null) {
                    if (s == null || s.getGrade() == null || !gradeId.equals(s.getGrade().getId())) continue;
                }
                out.add(paperDto(p, subjects));
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── writes (ADMIN tier — an exam decides what marks are possible) ───────────────────────────

    @RequestMapping(value = "/addExam", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public GenericResponse addExam(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String name = request.getParameter("name");
            if (!StringUtils.hasText(name)) return new GenericResponse("ERROR", "Exam name is required");

            // D3: an exam MUST sit in a term. Say so plainly rather than failing on a NOT NULL column.
            String termIdStr = request.getParameter("termId");
            if (!StringUtils.hasText(termIdStr)) {
                boolean anyTerms = !termRepository.findScoped(org, uid).isEmpty();
                return new GenericResponse("ERROR", anyTerms
                        ? "Select the term this exam belongs to."
                        : "Create an academic year and at least one term before adding exams.");
            }
            Term term = termRepository.findByIdScoped(Long.valueOf(termIdStr.trim()), org, uid).orElse(null);
            if (term == null) return new GenericResponse("ERROR", "Term not found");

            String idStr = request.getParameter("id");
            Exam exam;
            Set<String> changed = new LinkedHashSet<>();
            if (StringUtils.hasText(idStr)) {
                // Anti-IDOR: unscoped findById here would let a caller re-parent another tenant's exam.
                exam = examRepository.findByIdScoped(Long.valueOf(idStr.trim()), org, uid).orElse(null);
                if (exam == null) return new GenericResponse("NOT_FOUND", "Exam not found");
                if (!term.getId().equals(exam.getTermId())) changed.add("termId");

                String refusal = ExamLockGuard.refusalFor(exam.getStatus(), changed);
                if (refusal != null) return new GenericResponse("FAILED", refusal);
            } else {
                exam = Exam.builder().userId(uid).organizationId(org)
                        .status(ExamStatus.DRAFT).dated(LocalDateTime.now()).build();
            }
            exam.setName(name.trim());
            exam.setType(request.getParameter("type"));
            exam.setTermId(term.getId());
            exam.setWeightPercent(parseInt(request.getParameter("weightPercent")));
            exam.setUpdated(LocalDateTime.now());
            examRepository.save(exam);

            // D4: weights that do not total 100 are a WARNING, never a block — a school is legitimately
            // mid-setup between creating the mid-term and the final. 1.5 is where a wrong total does harm.
            return new GenericResponse("SUCCESS", weightNotice(term, org, uid, "Exam saved"));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Sums the term's exam weights and appends a notice when they do not reach exactly 100. */
    private String weightNotice(Term term, Long org, Long uid, String okMessage) {
        int total = 0;
        for (Exam e : examRepository.findByTermScoped(term.getId(), org, uid)) {
            total += e.getWeightPercent() == null ? 0 : e.getWeightPercent();
        }
        if (total == 100 || total == 0) return okMessage;
        return okMessage + " — note: exams in " + term.getName() + " now total " + total + "% of the term.";
    }

    @RequestMapping(value = "/addExamPaper", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public GenericResponse addExamPaper(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String examIdStr = request.getParameter("examId");
            String subjectIdStr = request.getParameter("subjectId");
            if (!StringUtils.hasText(examIdStr)) return new GenericResponse("ERROR", "Exam is required");
            if (!StringUtils.hasText(subjectIdStr)) return new GenericResponse("ERROR", "Subject is required");

            Exam exam = examRepository.findByIdScoped(Long.valueOf(examIdStr.trim()), org, uid).orElse(null);
            if (exam == null) return new GenericResponse("NOT_FOUND", "Exam not found");
            // The subject must be this tenant's too — otherwise a paper could reference another school's
            // subject, which is the save-takeover shape finding A was about.
            Subject subject = subjectRepository.findByIdScoped(Long.valueOf(subjectIdStr.trim()), org, uid).orElse(null);
            if (subject == null) return new GenericResponse("NOT_FOUND", "Subject not found");

            Integer maxMarks = parseInt(request.getParameter("maxMarks"));
            Integer passMarks = parseInt(request.getParameter("passMarks"));
            if (maxMarks != null && passMarks != null && passMarks > maxMarks) {
                return new GenericResponse("ERROR", "Pass marks cannot exceed maximum marks");
            }

            String idStr = request.getParameter("id");
            ExamPaper paper;
            Set<String> changed = new LinkedHashSet<>();
            if (StringUtils.hasText(idStr)) {
                paper = examPaperRepository.findByIdScoped(Long.valueOf(idStr.trim()), org, uid).orElse(null);
                if (paper == null) return new GenericResponse("NOT_FOUND", "Paper not found");
                if (!java.util.Objects.equals(paper.getMaxMarks(), maxMarks)) changed.add("maxMarks");
                if (!java.util.Objects.equals(paper.getPassMarks(), passMarks)) changed.add("passMarks");
                if (!java.util.Objects.equals(paper.getSubjectId(), subject.getId())) changed.add("subjectId");
            } else {
                paper = ExamPaper.builder().userId(uid).organizationId(org)
                        .examId(exam.getId()).dated(LocalDateTime.now()).build();
                // A NEW paper on a locked exam adds marks capacity that was never examined — same harm.
                changed.add("maxMarks");
            }

            // D5: the ONE place the rule is applied. Rescheduling a locked paper stays allowed.
            String refusal = ExamLockGuard.refusalFor(exam.getStatus(), changed);
            if (refusal != null) return new GenericResponse("FAILED", refusal);

            paper.setExamId(exam.getId());
            paper.setSubjectId(subject.getId());
            paper.setMaxMarks(maxMarks);
            paper.setPassMarks(passMarks);
            paper.setExamDate(parseDate(request.getParameter("examDateStr")));
            paper.setTimeFrom(parseTime(request.getParameter("timeFrom")));
            paper.setTimeTo(parseTime(request.getParameter("timeTo")));
            paper.setUpdated(LocalDateTime.now());
            examPaperRepository.save(paper);
            return new GenericResponse("SUCCESS", "Paper saved");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Move an exam through DRAFT → PUBLISHED → LOCKED, or unlock it.
     * Unlocking is deliberate and ADMIN-only; 1.3's audit hook should cover it, because it is the one
     * action that re-opens results to silent restatement.
     */
    @RequestMapping(value = "/setExamStatus", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public GenericResponse setExamStatus(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String idStr = request.getParameter("id");
            String statusStr = request.getParameter("status");
            if (!StringUtils.hasText(idStr)) return new GenericResponse("ERROR", "Exam is required");

            Exam exam = examRepository.findByIdScoped(Long.valueOf(idStr.trim()), org, uid).orElse(null);
            if (exam == null) return new GenericResponse("NOT_FOUND", "Exam not found");

            ExamStatus target;
            try {
                target = ExamStatus.valueOf(String.valueOf(statusStr).trim().toUpperCase());
            } catch (Exception ex) {
                return new GenericResponse("ERROR", "Unknown status: " + statusStr);
            }
            ExamStatus previous = exam.getStatus();
            exam.setStatus(target);
            exam.setUpdated(LocalDateTime.now());
            examRepository.save(exam);

            // Slice 1.3 (D5), the commitment 1.2 deferred: unlocking re-opens results to silent
            // restatement, so it is the single most important status change to have on the record.
            if (previous != target && (target == ExamStatus.LOCKED || previous == ExamStatus.LOCKED)) {
                auditService.record(target == ExamStatus.LOCKED ? "EXAM_LOCKED" : "EXAM_UNLOCKED",
                        "Exam", String.valueOf(exam.getId()),
                        "status " + (previous == null ? "(none)" : previous.name()) + " → " + target.name());
            }
            return new GenericResponse("SUCCESS", "Exam is now " + target.name());
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── deletes (DELETE tier) ───────────────────────────────────────────────────────────────────

    /** Deleting an exam takes its papers with it — an orphan paper points at an exam that cannot be read. */
    @RequestMapping(value = "/deleteExam", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @Transactional
    public GenericResponse deleteExam(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String checked = request.getParameter("checked");
            if (!StringUtils.hasText(checked)) return new GenericResponse("SUCCESS", "Nothing to delete");

            int exams = 0, papers = 0;
            for (String raw : checked.split(",")) {
                if (!StringUtils.hasText(raw)) continue;
                Exam exam = examRepository.findByIdScoped(Long.valueOf(raw.trim()), org, uid).orElse(null);
                if (exam == null) continue;   // not this tenant's — skip silently, same as ScopedDeleter
                papers += examPaperRepository.deleteByExamScoped(exam.getId(), org, uid);
                examRepository.delete(exam);
                exams++;
            }
            return new GenericResponse("SUCCESS", exams + " exam(s) and " + papers + " paper(s) deleted");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteExamPaper", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    public GenericResponse deleteExamPaper(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String checked = request.getParameter("checked");
            if (!StringUtils.hasText(checked)) return new GenericResponse("SUCCESS", "Nothing to delete");
            int n = 0;
            for (String raw : checked.split(",")) {
                if (!StringUtils.hasText(raw)) continue;
                ExamPaper p = examPaperRepository.findByIdScoped(Long.valueOf(raw.trim()), org, uid).orElse(null);
                if (p == null) continue;
                Exam exam = examRepository.findByIdScoped(p.getExamId(), org, uid).orElse(null);
                // Removing a paper from a locked exam destroys the marks recorded against it.
                String refusal = exam == null ? null
                        : ExamLockGuard.refusalFor(exam.getStatus(), java.util.Collections.singleton("maxMarks"));
                if (refusal != null) return new GenericResponse("FAILED", refusal);
                examPaperRepository.delete(p);
                n++;
            }
            return new GenericResponse("SUCCESS", n + " paper(s) deleted");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }
}
