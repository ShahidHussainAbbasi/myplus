package com.myplus.education.service;

import java.time.LocalDate;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.education.entity.*;
import com.myplus.education.repository.*;

/**
 * Slice 3.3 — <b>the ONE renderer behind every portal read, for every audience.</b>
 * Design: microservices/docs/slices/edu-3.3-student-portal.md (finding B)
 *
 * <h3>Why this exists</h3>
 *
 * 3.1 wrote these reads inside {@code GuardianPortalController}, keyed on one enrolment number that
 * {@code ChildResolver} had already proven belonged to the caller. A student portal needs the same answers
 * for one enrolment number proven a different way:
 *
 * <pre>
 *   guardian → ChildResolver.requireMine(enrollNo)  ─┐
 *                                                    ├─→ THE SAME READ
 *   student  → StudentResolver.me() → my enrollNo   ─┘
 * </pre>
 *
 * Copying them would have been this codebase's first regression against its own "extract at the second
 * caller" record — the rule that produced {@code StaffAbsenceService} (2.3), {@code StudentVisibilityService}
 * (1.5) and three shared libraries, and that the standards review calls exemplary.
 *
 * <h3>The division of responsibility, stated because it is the security boundary</h3>
 *
 * <b>This class renders; it never decides whose data it is.</b> Every method takes an {@code enrollNo} (or a
 * {@code Student}) that the CALLER has already authorised, and none of them reads the request, the session
 * or the security context. That keeps authority in exactly two reviewable places — {@code ChildResolver} and
 * {@code StudentResolver} — instead of spreading it across every read.
 *
 * <p>Consequence worth naming: <b>passing an unauthorised enrolment number here returns that student's
 * data.</b> That is by design and is why the two resolvers exist; it is also why nothing else may call this
 * class without going through one of them.
 */
@Service
public class PortalReadService {

    @Autowired private ReportCardRepository reportCardRepository;
    @Autowired private ReportCardLineRepository reportCardLineRepository;
    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private FeeCollectionRepository feeCollectionRepository;
    @Autowired private HomeworkRepository homeworkRepository;
    @Autowired private HomeworkSubmissionRepository submissionRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private TimetableEntryRepository timetableEntryRepository;
    @Autowired private PeriodRepository periodRepository;
    @Autowired private NoticeRepository noticeRepository;

    /**
     * Results: <b>PUBLISHED report cards only</b>.
     *
     * <p>1.5 made an issued card a snapshot precisely so it could be shown outside the school. A DRAFT or
     * SUPERSEDED card must never appear here — someone seeing a mark that later changes is exactly the harm
     * snapshotting prevents. Unchanged from 3.1; moved, not rewritten.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> results(Long orgId, String enrollNo) {
        List<ReportCard> cards = new ArrayList<>();
        for (ReportCard c : reportCardRepository.findByStudentScoped(enrollNo, orgId, null)) {
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
        return out;
    }

    /** Attendance: a present/total summary plus the recent days, for one student. */
    @Transactional(readOnly = true)
    public Map<String, Object> attendance(Long orgId, String enrollNo) {
        int present = 0, total = 0;
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Attendance a : attendanceRepository.findScoped(orgId, null)) {
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
        return out;
    }

    /**
     * Fee dues, read-only.
     *
     * <p><b>Guardian-only by policy, not by plumbing (3.3 D4).</b> Nothing here would stop a student
     * endpoint calling it — the refusal is that no student endpoint does, and the gate asserts that. A
     * family's financial position is the guardian's business, and a child reading "your family owes 40,000"
     * is a harm the system would be creating rather than reporting.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> dues(Long orgId, String enrollNo) {
        long outstanding = 0, paid = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FeeCollection f : feeCollectionRepository.findScoped(orgId, null)) {
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
        return out;
    }

    /** Homework set for this student's class, with whatever has been recorded for them. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> homework(Long orgId, Student student) {
        String enrollNo = student.getEnrollNo();

        // The class → its subjects → their homework, in one pass each.
        List<Long> subjectIds = new ArrayList<>();
        Map<Long, String> subjectNames = new HashMap<>();
        for (Subject s : subjectRepository.findScoped(orgId, null)) {
            Long gradeId = s.getGrade() == null ? null : s.getGrade().getId();
            if (student.getGradeId() != null && student.getGradeId().equals(gradeId)) {
                subjectIds.add(s.getId());
                subjectNames.put(s.getId(), s.getName());
            }
        }
        List<Homework> tasks = subjectIds.isEmpty() ? List.of()
                : homeworkRepository.findBySubjectsScoped(subjectIds, orgId, null);

        Map<Long, HomeworkSubmission> mine = new HashMap<>();
        if (!tasks.isEmpty()) {
            List<Long> taskIds = new ArrayList<>();
            for (Homework h : tasks) taskIds.add(h.getId());
            for (HomeworkSubmission s : submissionRepository.findByHomeworkIdsScoped(taskIds, orgId, null)) {
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
        return out;
    }

    /**
     * Slice 3.5 — the notices this caller may see, newest first, pinned ones on top.
     *
     * <p><b>The audience filter IS the authorisation here</b> (3.5 D5): reading a notice is not privilege
     * gated, so {@code NoticeAudienceResolver.reaches} is the only thing standing between a GUARDIANS-only
     * notice and a student's screen. That is why it is a pure, unit-tested static rather than a predicate
     * inlined below.
     *
     * <p>Filtered in memory after ONE scoped query, deliberately: the alternative is a query variant per
     * audience per caller type, and a notice list is small and read rarely compared with a timetable. If
     * that ever stops being true, the fix is an indexed predicate — not a second copy of the rule.
     *
     * @param callerGrade the asker's class — a student's own, or a guardian's child's. Null is legitimate
     *                    (a guardian with children in several classes, or a student not yet in one) and
     *                    simply never matches a ONE_CLASS notice.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> notices(Long orgId, PortalSubjectType subjectType, Long callerGrade) {
        if (orgId == null || subjectType == null) return List.of();

        LocalDate today = LocalDate.now();
        List<Notice> pinned = new ArrayList<>();
        List<Notice> rest = new ArrayList<>();
        for (Notice n : noticeRepository.findPublishedForPortal(orgId, NoticeStatus.PUBLISHED)) {
            if (!NoticeAudienceResolver.reaches(n, subjectType, callerGrade)) continue;
            boolean isPinned = n.getPinnedUntil() != null && !n.getPinnedUntil().isBefore(today);
            (isPinned ? pinned : rest).add(n);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Notice n : pinned) out.add(noticeMap(n, true));
        for (Notice n : rest) out.add(noticeMap(n, false));
        return out;
    }

    private Map<String, Object> noticeMap(Notice n, boolean pinned) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", n.getTitle());
        m.put("body", n.getBody());
        m.put("publishedOn", n.getPublishedOn() == null ? null : n.getPublishedOn().toString());
        m.put("pinned", pinned);
        // No author, no audience, no internal ids: a family needs the message and the date. Everything
        // else is school-side information that a portal read has no reason to carry.
        return m;
    }

    /**
     * The student's week — <b>new in 3.3</b>, and the answer to "where am I next".
     *
     * <p>One query: 2.1's {@code findByGradeScoped} already exists, is org-scoped and is indexed, so a
     * class's week costs one round trip rather than one per day. Period names and times are resolved from a
     * single map rather than per row — the N+1 shape 1.5 was caught by.
     *
     * <p><b>Rooms are included and teacher names are not.</b> A room tells a student where to go; naming
     * which teacher is with them all week, on a surface reachable from outside the school, is staff
     * information a timetable does not need to publish to be useful.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> timetable(Long orgId, Long gradeId, Long termId) {
        if (gradeId == null) return List.of();

        Map<Long, Period> periods = new HashMap<>();
        for (Period p : periodRepository.findScoped(orgId, null)) periods.put(p.getId(), p);

        Map<Long, String> subjectNames = new HashMap<>();
        for (Subject s : subjectRepository.findScoped(orgId, null)) subjectNames.put(s.getId(), s.getName());

        List<Map<String, Object>> out = new ArrayList<>();
        for (TimetableEntry t : timetableEntryRepository.findByGradeScoped(gradeId, termId, orgId, null)) {
            Period p = periods.get(t.getPeriodId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dayOfWeek", t.getDayOfWeek() == null ? null : t.getDayOfWeek().name());
            m.put("periodName", p == null ? null : p.getName());
            m.put("startTime", p == null || p.getStartTime() == null ? null : p.getStartTime().toString());
            m.put("endTime", p == null || p.getEndTime() == null ? null : p.getEndTime().toString());
            m.put("subjectName", subjectNames.get(t.getSubjectId()));
            m.put("room", t.getRoom());
            out.add(m);
        }
        return out;
    }
}
