package com.myplus.education.controller;

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
import com.myplus.common.settings.SettingsService;
import com.myplus.education.entity.*;
import com.myplus.education.repository.*;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.service.ReportCardService;
import com.myplus.education.service.StudentVisibilityService;
import com.myplus.education.service.TermAggregator;
import com.myplus.education.service.TermAggregator.LineView;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 1.5 — report cards and the cumulative transcript.
 * Design: microservices/docs/slices/edu-1.5-report-cards.md
 *
 * Two things this controller is careful about:
 *
 * <p><b>Preview is a query; publishing is an act.</b> Preview recomputes from live marks and stores
 * nothing. Publishing writes a snapshot that is never recomputed (D1) — so publishing is
 * {@code ADMIN_PRIVILEGE}, the same tier as fee settings, because issuing a result to a guardian is the
 * same class of act as changing what they owe.
 *
 * <p><b>Weights must total 100 to publish (D2).</b> 1.2 chose to warn, which is right on the exam screen
 * and wrong here: a weighted total built from weights summing to 70 is not a partial answer, it is a
 * wrong number that looks like a right one — and once it is on paper there is no recall.
 */
@Controller
public class ReportCardController {

    /** D4 — publishing rank is prohibited in several jurisdictions, so it is opt-in. */
    public static final String SHOW_RANK = "edu.reportCard.showRank";
    public static final String SHOW_ATTENDANCE = "edu.reportCard.showAttendance";

    @Autowired private ReportCardService reportCardService;
    @Autowired private ReportCardRepository reportCardRepository;
    @Autowired private ReportCardLineRepository reportCardLineRepository;
    @Autowired private TermRepository termRepository;
    @Autowired private GradeRepository gradeRepository;
    @Autowired private ExamRepository examRepository;
    @Autowired private StudentVisibilityService studentVisibilityService;
    @Autowired private EduAuditService auditService;
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

    private boolean showRank() {
        // Fails CLOSED: if the setting cannot be read, rank stays hidden. The failure mode of guessing
        // wrong in the other direction is publishing something a jurisdiction forbids.
        try { return settingsService.getBool(SHOW_RANK); } catch (Exception e) { return false; }
    }

    private boolean showAttendance() {
        try { return settingsService.getBool(SHOW_ATTENDANCE); } catch (Exception e) { return true; }
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────────

    /**
     * A card as it WOULD be issued today, computed live. Nothing is stored (D1).
     *
     * Ungated beyond authentication, consistent with every other education read (and with
     * {@code getMarksSheet}, which already shows the same marks). Scope still applies: a student outside
     * the caller's branch answers NOT_FOUND.
     */
    @RequestMapping(value = "/getReportCardPreview", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getReportCardPreview(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String enrollNo = request.getParameter("enrollNo");
            Long termId = parseLong(request.getParameter("termId"));
            if (!StringUtils.hasText(enrollNo)) return new GenericResponse("ERROR", "Student is required");
            if (termId == null) return new GenericResponse("ERROR", "Term is required");
            if (!studentVisibilityService.isVisible(org, uid, enrollNo)) {
                return new GenericResponse("NOT_FOUND", "Student not found");
            }
            Term term = termRepository.findByIdScoped(termId, org, uid).orElse(null);
            if (term == null) return new GenericResponse("NOT_FOUND", "Term not found");

            Student student = findVisible(org, uid, enrollNo);
            ReportCardService.TermData data = reportCardService.loadTerm(org, uid, termId);
            List<LineView> lines = reportCardService.linesFor(data, enrollNo.trim(),
                    student == null ? null : student.getGradeId());

            Map<String, Object> out = cardPayload(term, student, lines, data);

            // The shortfall is NAMED rather than merely flagged: "70%" with no explanation reads as a bug.
            int total = data.weightTotal();
            out.put("weightTotal", total);
            out.put("publishable", total == 100);
            if (total != 100) {
                out.put("weightWarning", weightMessage(term, total, data));
            }
            if (showRank()) {
                out.put("classRank", previewRank(org, uid, data, student, enrollNo.trim()));
            }
            if (showAttendance()) {
                int[] att = reportCardService.attendanceFor(org, uid, term).get(enrollNo.trim());
                out.put("attendancePresent", att == null ? null : att[0]);
                out.put("attendanceTotal", att == null ? null : att[1]);
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * An ISSUED card, read from its snapshot (D1). This is the read that must never recompute: reopening
     * a card after the scale was re-banded has to show the letters as awarded.
     */
    @RequestMapping(value = "/getReportCard", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getReportCard(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            ReportCard card;
            Long id = parseLong(request.getParameter("id"));
            if (id != null) {
                card = reportCardRepository.findByIdScoped(id, org, uid).orElse(null);
            } else {
                String enrollNo = request.getParameter("enrollNo");
                Long termId = parseLong(request.getParameter("termId"));
                if (!StringUtils.hasText(enrollNo) || termId == null) {
                    return new GenericResponse("ERROR", "Student and term are required");
                }
                card = reportCardRepository.findCurrentScoped(enrollNo.trim(), termId,
                        ReportCardStatus.PUBLISHED, org, uid).orElse(null);
            }
            if (card == null) return new GenericResponse("NOT_FOUND", "No report card has been issued");
            if (!studentVisibilityService.isVisible(org, uid, card.getStudentEnrollNo())) {
                return new GenericResponse("NOT_FOUND", "No report card has been issued");
            }
            return new GenericResponse("SUCCESS", "", snapshotPayload(card));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * The cumulative transcript — every card a student has been ISSUED, newest first (D6).
     *
     * Reads snapshots and recomputes nothing. A transcript spanning five years crosses at least one
     * grading-scale change in any real school; re-deriving it would restate a child's entire history
     * against today's bands.
     */
    @RequestMapping(value = "/getTranscript", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getTranscript(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String enrollNo = request.getParameter("enrollNo");
            if (!StringUtils.hasText(enrollNo)) return new GenericResponse("ERROR", "Student is required");
            if (!studentVisibilityService.isVisible(org, uid, enrollNo)) {
                return new GenericResponse("NOT_FOUND", "Student not found");
            }
            boolean includeSuperseded = "true".equalsIgnoreCase(request.getParameter("includeSuperseded"));

            List<ReportCard> cards = new ArrayList<>();
            for (ReportCard c : reportCardRepository.findByStudentScoped(enrollNo.trim(), org, uid)) {
                if (!includeSuperseded && c.getStatus() != ReportCardStatus.PUBLISHED) continue;
                cards.add(c);
            }
            // D8: lines for the whole transcript in ONE query, not one per card.
            List<Long> ids = new ArrayList<>();
            for (ReportCard c : cards) ids.add(c.getId());
            Map<Long, List<ReportCardLine>> linesByCard = new HashMap<>();
            if (!ids.isEmpty()) {
                for (ReportCardLine l : reportCardLineRepository.findByCardIds(ids)) {
                    linesByCard.computeIfAbsent(l.getReportCardId(), k -> new ArrayList<>()).add(l);
                }
            }

            List<Map<String, Object>> out = new ArrayList<>();
            for (ReportCard c : cards) {
                out.add(snapshotPayload(c, linesByCard.getOrDefault(c.getId(), List.of())));
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── publishing (ADMIN tier — issuing a result to a guardian) ──────────────────────────────────

    /**
     * Issue one card. Refuses when the term's exam weights do not total 100 (D2).
     *
     * Rank is computed across the student's whole class here, not just the students already published —
     * a position relative to a partially-published class would be meaningless.
     */
    @RequestMapping(value = "/publishReportCard", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse publishReportCard(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String enrollNo = request.getParameter("enrollNo");
            Long termId = parseLong(request.getParameter("termId"));
            if (!StringUtils.hasText(enrollNo)) return new GenericResponse("ERROR", "Student is required");
            if (termId == null) return new GenericResponse("ERROR", "Term is required");
            if (!studentVisibilityService.isVisible(org, uid, enrollNo)) {
                return new GenericResponse("NOT_FOUND", "Student not found");
            }
            Term term = termRepository.findByIdScoped(termId, org, uid).orElse(null);
            if (term == null) return new GenericResponse("NOT_FOUND", "Term not found");

            ReportCardService.TermData data = reportCardService.loadTerm(org, uid, termId);
            int total = data.weightTotal();
            if (total != 100) {
                return new GenericResponse("FAILED", weightMessage(term, total, data));
            }

            Student student = findVisible(org, uid, enrollNo.trim());
            if (student == null) return new GenericResponse("NOT_FOUND", "Student not found");
            List<LineView> lines = reportCardService.linesFor(data, enrollNo.trim(), student.getGradeId());
            if (lines.isEmpty()) {
                return new GenericResponse("FAILED",
                        "No marks have been entered for this student in " + term.getName() + ".");
            }

            Map<String, Integer> ranks = classRanks(org, uid, data, student.getGradeId());
            int[] att = showAttendance()
                    ? reportCardService.attendanceFor(org, uid, term).get(enrollNo.trim()) : null;

            ReportCard card = reportCardService.publish(org, uid, enrollNo.trim(), student.getName(), term,
                    student.getGradeId(), gradeName(org, uid, student.getGradeId()),
                    lines, ranks.get(enrollNo.trim()), classSize(org, uid, student.getGradeId()), att);

            auditService.record("REPORT_CARD_PUBLISHED", "ReportCard", String.valueOf(card.getId()),
                    "student=" + enrollNo.trim() + " term=" + term.getName()
                            + " version=" + card.getVersion() + " percent=" + card.getTermPercent());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", card.getId());
            out.put("version", card.getVersion());
            return new GenericResponse("SUCCESS",
                    "Report card issued (version " + card.getVersion() + ")", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Withdraw an issued card. The row is NOT deleted — it becomes WITHDRAWN (D5), because a card that
     * was handed out existed, and deleting the record is how a school loses the ability to explain itself.
     */
    @RequestMapping(value = "/withdrawReportCard", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse withdrawReportCard(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long id = parseLong(request.getParameter("id"));
            if (id == null) return new GenericResponse("ERROR", "Card is required");
            ReportCard card = reportCardRepository.findByIdScoped(id, org, uid).orElse(null);
            if (card == null) return new GenericResponse("NOT_FOUND", "Report card not found");
            if (card.getStatus() != ReportCardStatus.PUBLISHED) {
                return new GenericResponse("FAILED", "Only a published card can be withdrawn");
            }
            card.setStatus(ReportCardStatus.WITHDRAWN);
            card.setUpdated(LocalDateTime.now());
            reportCardRepository.save(card);
            auditService.record("REPORT_CARD_WITHDRAWN", "ReportCard", String.valueOf(card.getId()),
                    "student=" + card.getStudentEnrollNo() + " version=" + card.getVersion());
            return new GenericResponse("SUCCESS", "Report card withdrawn");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private static Long parseLong(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return Long.valueOf(s.trim()); } catch (Exception e) { return null; }
    }

    private Student findVisible(Long org, Long uid, String enrollNo) {
        String wanted = enrollNo.trim();
        for (Student s : studentVisibilityService.visibleStudents(org, uid)) {
            if (wanted.equals(s.getEnrollNo())) return s;
        }
        return null;
    }

    private String gradeName(Long org, Long uid, Long gradeId) {
        if (gradeId == null) return null;
        Grade g = gradeRepository.findByIdScoped(gradeId, org, uid).orElse(null);
        return g == null ? null : g.getName();
    }

    private int classSize(Long org, Long uid, Long gradeId) {
        int n = 0;
        for (Student s : studentVisibilityService.visibleStudents(org, uid)) {
            if (gradeId == null || gradeId.equals(s.getGradeId())) n++;
        }
        return n;
    }

    /** Every classmate's term percentage, ranked together — one pass over the term data already loaded. */
    private Map<String, Integer> classRanks(Long org, Long uid, ReportCardService.TermData data, Long gradeId) {
        Map<String, Double> percents = new LinkedHashMap<>();
        for (Student s : studentVisibilityService.visibleStudents(org, uid)) {
            if (gradeId != null && !gradeId.equals(s.getGradeId())) continue;
            if (s.getEnrollNo() == null || s.getEnrollNo().isBlank()) continue;
            List<LineView> lines = reportCardService.linesFor(data, s.getEnrollNo(), s.getGradeId());
            percents.put(s.getEnrollNo(), TermAggregator.weightedTermPercent(lines, data.weightByExam));
        }
        return TermAggregator.rank(percents);
    }

    private Integer previewRank(Long org, Long uid, ReportCardService.TermData data,
                                Student student, String enrollNo) {
        if (student == null) return null;
        return classRanks(org, uid, data, student.getGradeId()).get(enrollNo);
    }

    private String weightMessage(Term term, int total, ReportCardService.TermData data) {
        StringBuilder sb = new StringBuilder();
        sb.append(term.getName()).append(" exam weights total ").append(total)
          .append("%, not 100%. Adjust the exam weights before issuing report cards.");
        if (!data.exams.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Exam e : data.exams) {
                parts.add(e.getName() + " " + (e.getWeightPercent() == null ? 0 : e.getWeightPercent()) + "%");
            }
            sb.append(" (").append(String.join(", ", parts)).append(")");
        }
        return sb.toString();
    }

    /** A computed (unissued) card. */
    private Map<String, Object> cardPayload(Term term, Student student, List<LineView> lines,
                                            ReportCardService.TermData data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issued", false);
        out.put("enrollNo", student == null ? null : student.getEnrollNo());
        out.put("studentName", student == null ? null : student.getName());
        out.put("termId", term.getId());
        out.put("termName", term.getName());
        Double pct = TermAggregator.weightedTermPercent(lines, data.weightByExam);
        out.put("termPercent", pct);
        GradeBand band = null;
        for (GradeBand b : data.scale) {
            if (pct != null && b.getMinPercent() != null && b.getMaxPercent() != null
                    && pct >= b.getMinPercent() && pct <= b.getMaxPercent()) { band = b; break; }
        }
        out.put("termGradeName", band == null ? null : band.getName());
        out.put("termGpa", TermAggregator.meanGpa(lines));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LineView l : lines) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("examName", l.examName());
            r.put("subjectName", l.subjectName());
            r.put("maxMarks", l.maxMarks());
            r.put("marksObtained", l.marksObtained());
            r.put("absent", l.absent());
            r.put("percent", l.percent());
            r.put("grade", l.gradeName());
            r.put("gpaPoints", l.gpaPoints());
            rows.add(r);
        }
        out.put("rows", rows);
        return out;
    }

    private Map<String, Object> snapshotPayload(ReportCard card) {
        return snapshotPayload(card, reportCardLineRepository.findByReportCardIdOrderBySequenceAsc(card.getId()));
    }

    /** An ISSUED card, entirely from stored values — no recomputation anywhere in here (D1). */
    private Map<String, Object> snapshotPayload(ReportCard card, List<ReportCardLine> lines) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issued", true);
        out.put("id", card.getId());
        out.put("enrollNo", card.getStudentEnrollNo());
        out.put("studentName", card.getStudentName());
        out.put("termId", card.getTermId());
        out.put("termName", card.getTermName());
        out.put("gradeName", card.getGradeName());
        out.put("termPercent", card.getTermPercent());
        out.put("termGradeName", card.getTermGradeName());
        out.put("termGpa", card.getTermGpa());
        out.put("version", card.getVersion());
        out.put("status", card.getStatus() == null ? null : card.getStatus().name());
        out.put("issuedOn", card.getIssuedOn() == null ? null : card.getIssuedOn().toString());
        // The setting decides whether rank is RENDERED; the snapshot decides what it WAS (D4).
        if (showRank()) {
            out.put("classRank", card.getClassRank());
            out.put("classSize", card.getClassSize());
        }
        if (showAttendance()) {
            out.put("attendancePresent", card.getAttendancePresent());
            out.put("attendanceTotal", card.getAttendanceTotal());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportCardLine l : lines) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("examName", l.getExamName());
            r.put("subjectName", l.getSubjectName());
            r.put("maxMarks", l.getMaxMarks());
            r.put("marksObtained", l.getMarksObtained());
            r.put("absent", l.isAbsent());
            r.put("percent", l.getPercent());
            r.put("grade", l.getGradeName());
            r.put("gpaPoints", l.getGpaPoints());
            rows.add(r);
        }
        out.put("rows", rows);
        return out;
    }
}
