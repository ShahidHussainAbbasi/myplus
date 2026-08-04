package com.myplus.education.controller;

import java.util.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.*;
import com.myplus.education.repository.*;
import com.myplus.education.service.ChildResolver;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 3.1 — the guardian portal. <b>The first surface an outsider can reach.</b>
 * Design: microservices/docs/slices/edu-3.1-guardian-portal.md
 *
 * <h3>Why this is a separate controller and not a privilege on the existing ones (D2)</h3>
 *
 * The cheap option was to add a {@code GUARDIAN} role and {@code @PreAuthorize} the existing endpoints.
 * That makes every one of ~31 education controllers a place a guardian might reach, and relies on each of
 * them remembering to narrow an org-scoped query to two children.
 *
 * <b>The education review's finding A already proved that bet loses:</b> a scoping rule that had to be
 * remembered per controller was forgotten in seven of them. Repeating it with an external principal is
 * materially worse.
 *
 * <p>So this is an <b>allowlist</b>: the portal's attack surface is exactly what is written below, and a
 * new staff endpoint is not automatically a guardian endpoint. A guardian hitting a staff URL is refused
 * because it is not part of this surface — not because a role string happened not to match.
 *
 * <h3>Every read passes through {@link ChildResolver}</h3>
 *
 * No method here trusts a client-supplied enrolment number. Each intersects it with the set derived from
 * {@code Student.guardianId} on this request (D1), and answers {@code NOT_FOUND} — never {@code FORBIDDEN} —
 * when it does not belong, because "that child exists but is not yours" is itself a disclosure.
 *
 * <h3>Read-only</h3>
 *
 * Every mapping is GET. There is no write endpoint on the portal surface at all (D4).
 */
@Controller
public class GuardianPortalController {

    @Autowired private ChildResolver childResolver;
    @Autowired private ReportCardRepository reportCardRepository;
    @Autowired private ReportCardLineRepository reportCardLineRepository;
    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private FeeCollectionRepository feeCollectionRepository;
    @Autowired private HomeworkRepository homeworkRepository;
    @Autowired private HomeworkSubmissionRepository submissionRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private GradeRepository gradeRepository;
    @Autowired private EduAuditService auditService;
    @Autowired private RequestUtil requestUtil;
    @Autowired private AppUtil appUtil;

    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    private String email() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getEmail();
    }

    /**
     * The signed-in guardian, or null.
     *
     * <p>One shared entry point so no endpoint can forget the portal-enabled check, the revoked check, or
     * the email lookup. Every method below starts here.
     */
    private GuardianPortalAccess guardian() {
        return childResolver.resolveGuardian(orgId(), email());
    }

    /** The single refusal used for every unauthorised case — see the class javadoc on NOT_FOUND. */
    private GenericResponse notYours() {
        return new GenericResponse("NOT_FOUND", "Not found");
    }

    // ── who am I ────────────────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/portal/me", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse me() {
        try {
            GuardianPortalAccess g = guardian();
            if (g == null) return notYours();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("guardianName", g.getGuardianName());
            out.put("email", g.getEmail());
            out.put("status", g.getStatus() == null ? null : g.getStatus().name());
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** My children — the set everything else is filtered by. Derived now, never cached (D1). */
    @RequestMapping(value = "/portal/children", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse children() {
        try {
            GuardianPortalAccess g = guardian();
            if (g == null) return notYours();

            Map<Long, String> gradeNames = new HashMap<>();
            for (Grade gr : gradeRepository.findScoped(orgId(), null)) {
                gradeNames.put(gr.getId(), gradeLabel(gr));
            }

            List<Map<String, Object>> out = new ArrayList<>();
            for (Student s : childResolver.myChildren(orgId(), g.getGuardianId())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("enrollNo", s.getEnrollNo());
                m.put("name", s.getName());
                m.put("gradeName", gradeNames.get(s.getGradeId()));
                out.add(m);
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── one child's data — each intersected with the derived set ────────────────────────────────

    /**
     * Results: <b>PUBLISHED report cards only</b>.
     *
     * <p>1.5 made an issued card a snapshot precisely so it could be shown outside the school. A DRAFT or
     * SUPERSEDED card must never appear here — a guardian seeing a mark that later changes is exactly the
     * harm snapshotting prevents.
     */
    @RequestMapping(value = "/portal/results", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse results(final HttpServletRequest request) {
        try {
            String enrollNo = mineOrNull(request);
            if (enrollNo == null) return notYours();

            List<ReportCard> cards = new ArrayList<>();
            for (ReportCard c : reportCardRepository.findByStudentScoped(enrollNo, orgId(), null)) {
                if (c.getStatus() == ReportCardStatus.PUBLISHED) cards.add(c);
            }
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
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("termName", c.getTermName());
                m.put("termPercent", c.getTermPercent());
                m.put("termGradeName", c.getTermGradeName());
                m.put("issuedOn", c.getIssuedOn() == null ? null : c.getIssuedOn().toString());
                List<Map<String, Object>> rows = new ArrayList<>();
                for (ReportCardLine l : linesByCard.getOrDefault(c.getId(), List.of())) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("subjectName", l.getSubjectName());
                    r.put("marksObtained", l.getMarksObtained());
                    r.put("maxMarks", l.getMaxMarks());
                    r.put("grade", l.getGradeName());
                    rows.add(r);
                }
                m.put("rows", rows);
                out.add(m);
            }
            audit("PORTAL_READ_RESULTS", enrollNo);
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Attendance: a present/total summary plus the recent days, for one child. */
    @RequestMapping(value = "/portal/attendance", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse attendance(final HttpServletRequest request) {
        try {
            String enrollNo = mineOrNull(request);
            if (enrollNo == null) return notYours();

            int present = 0, total = 0;
            List<Map<String, Object>> recent = new ArrayList<>();
            for (Attendance a : attendanceRepository.findScoped(orgId(), null)) {
                if (!enrollNo.equals(a.getEn())) continue;
                total++;
                boolean isPresent = a.getStatus() != null
                        && (a.getStatus().equalsIgnoreCase("present") || a.getStatus().equalsIgnoreCase("p"));
                if (isPresent) present++;
                if (recent.size() < 30) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("date", a.getAttDate() == null ? null : a.getAttDate().toString());
                    m.put("status", a.getStatus());
                    recent.add(m);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("present", present);
            out.put("total", total);
            out.put("rate", total > 0 ? Math.round((present * 1000.0 / total)) / 10.0 : 0);
            out.put("recent", recent);
            audit("PORTAL_READ_ATTENDANCE", enrollNo);
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Fee dues, read-only.
     *
     * <p>D5 — the arithmetic is finance's (0.2a); the portal shows it. There is no Pay button: that is 3.2
     * and gated on D-4. Showing a balance a guardian cannot yet pay is still an improvement on today, where
     * they cannot see it at all.
     */
    @RequestMapping(value = "/portal/dues", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse dues(final HttpServletRequest request) {
        try {
            String enrollNo = mineOrNull(request);
            if (enrollNo == null) return notYours();

            long outstanding = 0, paid = 0;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (FeeCollection f : feeCollectionRepository.findScoped(orgId(), null)) {
                if (!enrollNo.equals(f.getEnrollNo())) continue;
                outstanding += f.getDueBalance() == null ? 0 : f.getDueBalance();
                paid += f.getFeePaid() == null ? 0 : f.getFeePaid();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("paymentDate", f.getPaymentDate() == null ? null : f.getPaymentDate().toString());
                m.put("fee", f.getFee());
                m.put("feePaid", f.getFeePaid());
                m.put("dueBalance", f.getDueBalance());
                rows.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("outstanding", outstanding);
            out.put("paid", paid);
            out.put("rows", rows);
            // No "payable" flag and no payment link: 3.2 owns that, and it is gated on D-4.
            audit("PORTAL_READ_DUES", enrollNo);
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Homework set for this child's class, with what has been recorded for them. */
    @RequestMapping(value = "/portal/homework", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse homework(final HttpServletRequest request) {
        try {
            String enrollNo = mineOrNull(request);
            if (enrollNo == null) return notYours();

            Student child = null;
            GuardianPortalAccess g = guardian();
            for (Student s : childResolver.myChildren(orgId(), g.getGuardianId())) {
                if (enrollNo.equals(s.getEnrollNo())) { child = s; break; }
            }
            if (child == null) return notYours();

            // The child's class → its subjects → their homework, in one pass each.
            List<Long> subjectIds = new ArrayList<>();
            Map<Long, String> subjectNames = new HashMap<>();
            for (Subject s : subjectRepository.findScoped(orgId(), null)) {
                Long gradeId = s.getGrade() == null ? null : s.getGrade().getId();
                if (child.getGradeId() != null && child.getGradeId().equals(gradeId)) {
                    subjectIds.add(s.getId());
                    subjectNames.put(s.getId(), s.getName());
                }
            }
            List<Homework> tasks = subjectIds.isEmpty() ? List.of()
                    : homeworkRepository.findBySubjectsScoped(subjectIds, orgId(), null);

            Map<Long, HomeworkSubmission> mine = new HashMap<>();
            if (!tasks.isEmpty()) {
                List<Long> taskIds = new ArrayList<>();
                for (Homework h : tasks) taskIds.add(h.getId());
                for (HomeworkSubmission s : submissionRepository
                        .findByHomeworkIdsScoped(taskIds, orgId(), null)) {
                    if (enrollNo.equals(s.getStudentEnrollNo())) mine.put(s.getHomeworkId(), s);
                }
            }

            List<Map<String, Object>> out = new ArrayList<>();
            for (Homework h : tasks) {
                HomeworkSubmission s = mine.get(h.getId());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", h.getTitle());
                m.put("subjectName", subjectNames.get(h.getSubjectId()));
                m.put("dueOn", h.getDueOn() == null ? null : h.getDueOn().toString());
                m.put("maxMarks", h.getMaxMarks());
                m.put("state", s == null ? null : s.getState().name());
                m.put("marksObtained", s == null ? null : s.getMarksObtained());
                m.put("feedback", s == null ? null : s.getFeedback());
                out.add(m);
            }
            audit("PORTAL_READ_HOMEWORK", enrollNo);
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // NOTE: there is deliberately NO behaviour-notes endpoint (D4). 2.5's log was written by staff with no
    // expectation that a guardian would read it the next day; exposing it retroactively would change the
    // contract its authors wrote under. It needs a per-note "shared with guardian" decision — a feature,
    // not a filter — and that is tracked in the programme's carried requirements.

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The gate. Resolves the guardian, then intersects the requested child with the derived set.
     *
     * <p>Every data endpoint above calls this FIRST and returns {@link #notYours()} on null. There is no
     * other way into a child's data from this controller.
     */
    private String mineOrNull(HttpServletRequest request) {
        GuardianPortalAccess g = guardian();
        if (g == null) return null;
        String requested = request.getParameter("enrollNo");
        if (!StringUtils.hasText(requested)) return null;
        return childResolver.requireMine(orgId(), g.getGuardianId(), requested);
    }

    /**
     * An external party reading a child's record is worth a permanent trail.
     *
     * <p>Best-effort: a failed audit must not deny a guardian their own child's results, and the outbox
     * retries. The read has already been authorised by this point.
     */
    private void audit(String action, String enrollNo) {
        try {
            auditService.record(action, "Student", enrollNo, "portal read by " + email());
        } catch (Exception e) {
            appUtil.le(getClass(), e);
        }
    }

    private String gradeLabel(Grade g) {
        String n = g.getName() == null ? "Class" : g.getName();
        return g.getSection() == null || g.getSection().isBlank() ? n : n + " " + g.getSection();
    }
}
