package com.myplus.education.controller;

import java.time.LocalDateTime;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.Exam;
import com.myplus.education.entity.ExamPaper;
import com.myplus.education.entity.ExamStatus;
import com.myplus.education.entity.Mark;
import com.myplus.education.entity.Student;
import com.myplus.education.entity.Subject;
import com.myplus.education.repository.ExamPaperRepository;
import com.myplus.education.repository.ExamRepository;
import com.myplus.education.repository.MarkRepository;
import com.myplus.education.repository.StudentRepository;
import com.myplus.education.repository.SubjectRepository;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.service.MarksValidator;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 1.3 — marks entry.
 * Design: microservices/docs/slices/edu-1.3-marks-entry.md
 *
 * Privilege tier (D6): entering marks is day-to-day teacher work → WRITE_PRIVILEGE, not ADMIN. Defining
 * the exam is ADMIN (1.2); filling it in is not. Scope comes from the existing branch machinery —
 * {@code visibleStudents()} plus Subject→Grade — so no new ownership concept is invented here.
 */
@Controller
public class MarkController {

    @Autowired private MarkRepository markRepository;
    @Autowired private ExamPaperRepository examPaperRepository;
    @Autowired private ExamRepository examRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private StudentRepository studentRepository;
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

    /** Branch-scoped roster — the same rule attendance uses, reused rather than re-derived (DRY). */
    private List<Student> visibleStudents() {
        if (requestUtil.isOwnerSuper()) return studentRepository.findScoped(orgId(), userId());
        Set<Long> schools = requestUtil.accessibleSchoolIds();
        if (schools.isEmpty()) return studentRepository.findScoped(orgId(), userId());
        return studentRepository.findScopedBySchools(orgId(), schools);
    }

    /** Request body for the grid save. */
    public static class BulkMarksRequest {
        private Long examPaperId;
        private List<Row> rows;
        public Long getExamPaperId() { return examPaperId; }
        public void setExamPaperId(Long examPaperId) { this.examPaperId = examPaperId; }
        public List<Row> getRows() { return rows; }
        public void setRows(List<Row> rows) { this.rows = rows; }

        public static class Row {
            private String enrollNo;
            private Integer marksObtained;
            private boolean absent;
            private String remarks;
            public String getEnrollNo() { return enrollNo; }
            public void setEnrollNo(String enrollNo) { this.enrollNo = enrollNo; }
            public Integer getMarksObtained() { return marksObtained; }
            public void setMarksObtained(Integer marksObtained) { this.marksObtained = marksObtained; }
            public boolean isAbsent() { return absent; }
            public void setAbsent(boolean absent) { this.absent = absent; }
            public String getRemarks() { return remarks; }
            public void setRemarks(String remarks) { this.remarks = remarks; }
        }
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The marksheet: the roster for the paper's class, plus whatever marks already exist.
     * {@code readOnly} tx because {@code Subject.grade} is LAZY under {@code open-in-view:false}.
     */
    @RequestMapping(value = "/getMarksSheet", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getMarksSheet(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String paperIdStr = request.getParameter("examPaperId");
            if (!StringUtils.hasText(paperIdStr)) return new GenericResponse("ERROR", "Paper is required");

            ExamPaper paper = examPaperRepository.findByIdScoped(Long.valueOf(paperIdStr.trim()), org, uid).orElse(null);
            if (paper == null) return new GenericResponse("NOT_FOUND", "Paper not found");
            Subject subject = subjectRepository.findByIdScoped(paper.getSubjectId(), org, uid).orElse(null);
            Long gradeId = subject == null || subject.getGrade() == null ? null : subject.getGrade().getId();

            // Existing marks, indexed so the roster loop stays O(n) rather than re-querying per student.
            Map<String, Mark> existing = new LinkedHashMap<>();
            for (Mark m : markRepository.findByPaperScoped(paper.getId(), org, uid)) {
                existing.put(m.getStudentEnrollNo(), m);
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            for (Student s : visibleStudents()) {
                if (appUtil.isEmptyOrNull(s.getEnrollNo())) continue;
                // The paper belongs to one class (derived via subject), so only that class is examined.
                if (gradeId != null && !gradeId.equals(s.getGradeId())) continue;
                Mark m = existing.get(s.getEnrollNo());
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("enrollNo", s.getEnrollNo());
                r.put("name", s.getName());
                r.put("marksObtained", m == null ? null : m.getMarksObtained());
                r.put("absent", m != null && m.isAbsent());
                r.put("remarks", m == null ? null : m.getRemarks());
                rows.add(r);
            }

            Exam exam = examRepository.findByIdScoped(paper.getExamId(), org, uid).orElse(null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("examPaperId", paper.getId());
            out.put("maxMarks", paper.getMaxMarks());
            out.put("passMarks", paper.getPassMarks());
            out.put("subjectName", subject == null ? null : subject.getName());
            out.put("gradeName", subject == null || subject.getGrade() == null ? null : subject.getGrade().getName());
            out.put("examName", exam == null ? null : exam.getName());
            out.put("examStatus", exam == null || exam.getStatus() == null ? null : exam.getStatus().name());
            out.put("rows", rows);
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** One student's marks across every paper — the read 1.5's transcript will build on. */
    @RequestMapping(value = "/getStudentMarks", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getStudentMarks(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String enrollNo = request.getParameter("enrollNo");
            if (!StringUtils.hasText(enrollNo)) return new GenericResponse("ERROR", "Enrolment number is required");

            // Branch scope: a teacher must not read a student outside their campus.
            boolean visible = visibleStudents().stream()
                    .anyMatch(s -> enrollNo.trim().equals(s.getEnrollNo()));
            if (!visible) return new GenericResponse("NOT_FOUND", "Student not found");

            List<Map<String, Object>> out = new ArrayList<>();
            for (Mark m : markRepository.findByStudentScoped(enrollNo.trim(), org, uid)) {
                ExamPaper p = examPaperRepository.findByIdScoped(m.getExamPaperId(), org, uid).orElse(null);
                Subject subj = p == null ? null
                        : subjectRepository.findByIdScoped(p.getSubjectId(), org, uid).orElse(null);
                Exam exam = p == null ? null
                        : examRepository.findByIdScoped(p.getExamId(), org, uid).orElse(null);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("markId", m.getId());
                r.put("examName", exam == null ? null : exam.getName());
                r.put("subjectName", subj == null ? null : subj.getName());
                r.put("marksObtained", m.getMarksObtained());
                r.put("absent", m.isAbsent());
                r.put("maxMarks", p == null ? null : p.getMaxMarks());
                r.put("passMarks", p == null ? null : p.getPassMarks());
                out.add(r);
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── the write (WRITE tier — day-to-day teacher work) ────────────────────────────────────────

    /**
     * Save a whole marksheet. D3: valid rows SAVE and invalid rows are reported PER STUDENT — rejecting
     * 40 rows because one cell says 105 would lose 39 correct entries and teach teachers to distrust the
     * save button, while accepting 105 silently is worse.
     */
    @RequestMapping(value = "/saveMarksBulk", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @Transactional
    public GenericResponse saveMarksBulk(@RequestBody BulkMarksRequest req) {
        try {
            if (req == null || req.getExamPaperId() == null) {
                return new GenericResponse("INVALID", "Paper is required");
            }
            Long org = orgId(), uid = userId();
            ExamPaper paper = examPaperRepository.findByIdScoped(req.getExamPaperId(), org, uid).orElse(null);
            if (paper == null) return new GenericResponse("NOT_FOUND", "Paper not found");

            Exam exam = examRepository.findByIdScoped(paper.getExamId(), org, uid).orElse(null);
            if (exam == null) return new GenericResponse("NOT_FOUND", "Exam not found");
            // D4: marks against an unpublished definition mean the datesheet the students saw was not the
            // one they were graded on.
            if (exam.getStatus() == ExamStatus.DRAFT) {
                return new GenericResponse("FAILED",
                        "Publish the exam before entering marks — a draft definition can still change.");
            }
            if (appUtil.isEmptyOrNull(req.getRows())) return new GenericResponse("INVALID", "Nothing to save");

            // Only students this caller may mark, and only those in the paper's class.
            Subject subject = subjectRepository.findByIdScoped(paper.getSubjectId(), org, uid).orElse(null);
            Long gradeId = subject == null || subject.getGrade() == null ? null : subject.getGrade().getId();
            Map<String, Student> roster = new LinkedHashMap<>();
            for (Student s : visibleStudents()) {
                if (appUtil.isEmptyOrNull(s.getEnrollNo())) continue;
                if (gradeId != null && !gradeId.equals(s.getGradeId())) continue;
                roster.put(s.getEnrollNo(), s);
            }

            Map<String, Mark> existing = new LinkedHashMap<>();
            for (Mark m : markRepository.findByPaperScoped(paper.getId(), org, uid)) {
                existing.put(m.getStudentEnrollNo(), m);
            }

            boolean hadMarksBefore = !existing.isEmpty();
            int saved = 0;
            List<String> errors = new ArrayList<>();

            for (BulkMarksRequest.Row row : req.getRows()) {
                if (row == null || appUtil.isEmptyOrNull(row.getEnrollNo())) continue;
                Student student = roster.get(row.getEnrollNo());
                if (student == null) {
                    // Outside this caller's branch, or not in the examined class — skip, do not leak why.
                    continue;
                }
                String problem = MarksValidator.validate(row.getMarksObtained(), row.isAbsent(), paper.getMaxMarks());
                if (problem != null) {
                    errors.add(student.getName() + " (" + row.getEnrollNo() + "): " + problem);
                    continue;
                }
                if (!MarksValidator.hasContent(row.getMarksObtained(), row.isAbsent(), row.getRemarks())
                        && !existing.containsKey(row.getEnrollNo())) {
                    continue;   // an untouched blank row is not an entry
                }

                Mark m = existing.get(row.getEnrollNo());
                Integer oldMarks = m == null ? null : m.getMarksObtained();
                boolean oldAbsent = m != null && m.isAbsent();
                boolean isNew = (m == null);
                if (isNew) {
                    m = Mark.builder()
                            .examPaperId(paper.getId())
                            .studentEnrollNo(row.getEnrollNo())
                            .organizationId(org)
                            .dated(LocalDateTime.now())
                            .build();
                }
                m.setUserId(uid);                       // audit: who last touched this mark
                m.setAbsent(row.isAbsent());
                // D2: absent is not a score — never persist 0 to mean "did not sit".
                m.setMarksObtained(row.isAbsent() ? null : row.getMarksObtained());
                m.setRemarks(row.getRemarks());
                m.setUpdated(LocalDateTime.now());
                markRepository.save(m);
                saved++;

                // D5: a CHANGE is materially different from an entry, and carries both values — an audit
                // recording only the new number cannot answer "was this altered?".
                String ref = "paper=" + paper.getId() + ",student=" + row.getEnrollNo();
                if (isNew) {
                    auditService.record("MARK_ENTERED", "Mark", ref,
                            "marks=" + describe(m.getMarksObtained(), m.isAbsent())
                                    + ", max=" + paper.getMaxMarks());
                } else if (!java.util.Objects.equals(oldMarks, m.getMarksObtained()) || oldAbsent != m.isAbsent()) {
                    auditService.record("MARK_CHANGED", "Mark", ref,
                            "from=" + describe(oldMarks, oldAbsent)
                                    + " to=" + describe(m.getMarksObtained(), m.isAbsent())
                                    + ", max=" + paper.getMaxMarks());
                }
            }

            // D4: the first mark LOCKS the exam. Deliberately automatic — leaving it to an admin means the
            // window between "marks entered" and "someone remembered" is exactly when maxMarks gets edited.
            if (saved > 0 && !hadMarksBefore && exam.getStatus() == ExamStatus.PUBLISHED) {
                exam.setStatus(ExamStatus.LOCKED);
                exam.setUpdated(LocalDateTime.now());
                examRepository.save(exam);
                auditService.record("EXAM_LOCKED", "Exam", String.valueOf(exam.getId()),
                        "locked automatically on first mark entry");
            }

            String msg = saved + " mark(s) saved";
            if (!errors.isEmpty()) {
                msg += " — " + errors.size() + " need attention: " + String.join("; ", errors);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("saved", saved);
            out.put("errors", errors);
            // PARTIAL, not SUCCESS: the UI must not round a partial write to "all saved" (design §7).
            return new GenericResponse(errors.isEmpty() ? "SUCCESS" : "PARTIAL", msg, out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            // Propagate past the @Transactional proxy so a partial batch is rolled back rather than committed.
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /** Renders a mark for the audit trail so "absent" and "0" can never read the same. */
    private static String describe(Integer marks, boolean absent) {
        if (absent) return "ABSENT";
        return marks == null ? "(blank)" : String.valueOf(marks);
    }
}
