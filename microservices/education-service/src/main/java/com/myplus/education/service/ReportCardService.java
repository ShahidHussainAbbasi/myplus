package com.myplus.education.service;

import com.myplus.education.entity.*;
import com.myplus.education.repository.*;
import com.myplus.education.service.TermAggregator.LineView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Slice 1.5 — builds a report card, and freezes one when it is issued.
 * Design: microservices/docs/slices/edu-1.5-report-cards.md
 *
 * The maths lives in {@link TermAggregator} (pure); the per-paper percentage and band come from
 * {@link GradingService} (1.4). This class owns only the data access — and specifically owns doing it in
 * BATCHES (D8): a term's papers, subjects, exams and marks are each read once, never once per student.
 */
@Service
public class ReportCardService {

    @Autowired private ExamRepository examRepository;
    @Autowired private ExamPaperRepository examPaperRepository;
    @Autowired private MarkRepository markRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private ReportCardRepository reportCardRepository;
    @Autowired private ReportCardLineRepository reportCardLineRepository;
    @Autowired private GradingService gradingService;

    /** Everything a term's cards are computed from, loaded ONCE (D8). */
    public static class TermData {
        public List<Exam> exams = List.of();
        public Map<Long, Exam> examById = new HashMap<>();
        public List<ExamPaper> papers = List.of();
        public Map<Long, ExamPaper> paperById = new HashMap<>();
        public Map<Long, String> subjectNameById = new HashMap<>();
        public Map<Long, Long> gradeIdBySubjectId = new HashMap<>();
        /** enrolment number → that student's marks across the whole term. */
        public Map<String, List<Mark>> marksByStudent = new HashMap<>();
        public Map<Long, Integer> weightByExam = new HashMap<>();
        public List<GradeBand> scale = List.of();

        public int weightTotal() {
            int t = 0;
            for (Integer w : weightByExam.values()) if (w != null) t += w;
            return t;
        }
    }

    /**
     * Load a whole term in a fixed number of queries, regardless of how many students or papers it has.
     *
     * Five queries: exams, papers, subjects, marks, bands. The read this replaces did three per MARK —
     * 30 marks for one student was 90 queries, and a class of 40 was ~3,600 (D8).
     */
    @Transactional(readOnly = true)
    public TermData loadTerm(Long orgId, Long userId, Long termId) {
        TermData d = new TermData();

        d.exams = examRepository.findByTermScoped(termId, orgId, userId);
        if (d.exams.isEmpty()) return d;
        List<Long> examIds = new ArrayList<>();
        for (Exam e : d.exams) {
            examIds.add(e.getId());
            d.examById.put(e.getId(), e);
            d.weightByExam.put(e.getId(), e.getWeightPercent());
        }

        d.papers = examPaperRepository.findByExamIdsScoped(examIds, orgId, userId);
        if (d.papers.isEmpty()) return d;
        List<Long> paperIds = new ArrayList<>();
        for (ExamPaper p : d.papers) {
            paperIds.add(p.getId());
            d.paperById.put(p.getId(), p);
        }

        // Subjects are read whole rather than by id-set: the tenant's subject list is small and this
        // resolves the LAZY Grade inside the transaction, which is what 1.2's subjectIndex() learned.
        for (Subject s : subjectRepository.findScoped(orgId, userId)) {
            d.subjectNameById.put(s.getId(), s.getName());
            d.gradeIdBySubjectId.put(s.getId(), s.getGrade() == null ? null : s.getGrade().getId());
        }

        for (Mark m : markRepository.findByPaperIdsScoped(paperIds, orgId, userId)) {
            d.marksByStudent.computeIfAbsent(m.getStudentEnrollNo(), k -> new ArrayList<>()).add(m);
        }

        d.scale = gradingService.scale(orgId, userId);
        return d;
    }

    /**
     * The subject rows for one student, in datesheet order.
     *
     * Only papers belonging to the student's own class are included — a paper's class is derived through
     * subject → grade (1.2 D2), never stored twice.
     */
    public List<LineView> linesFor(TermData d, String enrollNo, Long gradeId) {
        List<LineView> lines = new ArrayList<>();
        List<Mark> marks = d.marksByStudent.getOrDefault(enrollNo, List.of());
        Map<Long, Mark> markByPaper = new HashMap<>();
        for (Mark m : marks) markByPaper.put(m.getExamPaperId(), m);

        int seq = 0;
        for (ExamPaper p : d.papers) {                       // already in datesheet order
            Long paperGrade = d.gradeIdBySubjectId.get(p.getSubjectId());
            if (gradeId != null && paperGrade != null && !gradeId.equals(paperGrade)) continue;

            Mark m = markByPaper.get(p.getId());
            if (m == null) continue;                          // not marked at all: not a row on the card
            Double pct = gradingService.percentFor(m, p);
            GradeBand band = gradingService.bandFor(d.scale, pct);
            Exam exam = d.examById.get(p.getExamId());
            lines.add(new LineView(
                    p.getExamId(),
                    exam == null ? null : exam.getName(),
                    d.subjectNameById.get(p.getSubjectId()),
                    p.getMaxMarks(),
                    m.getMarksObtained(),
                    m.isAbsent(),
                    pct,
                    band == null ? null : band.getName(),
                    band == null ? null : band.getGpaPoints(),
                    seq++));
        }
        return lines;
    }

    /**
     * Per-student attendance for the term, as ONE grouped query for the whole tenant.
     *
     * Keyed on the term's DATE RANGE rather than {@code term_id} — see the repository javadoc: 1.1 added
     * the column but deliberately never backfilled it, so a term predating 1.1 would report 0/0.
     * A term with no dates yields an empty map rather than a wrong number.
     *
     * @return enrolment number → {@code [present, total]}
     */
    @Transactional(readOnly = true)
    public Map<String, int[]> attendanceFor(Long orgId, Long userId, Term term) {
        Map<String, int[]> out = new HashMap<>();
        if (term == null || term.getStartDate() == null || term.getEndDate() == null) return out;
        for (Object[] row : attendanceRepository.summariseByStudent(
                term.getStartDate(), term.getEndDate(), orgId, userId)) {
            String en = (String) row[0];
            int present = row[1] == null ? 0 : ((Number) row[1]).intValue();
            int total = row[2] == null ? 0 : ((Number) row[2]).intValue();
            if (en != null) out.put(en, new int[] { present, total });
        }
        return out;
    }

    // ── publishing ──────────────────────────────────────────────────────────────────────────────

    /**
     * Freeze a computed card (D1). Names are copied in as VALUES; nothing here is a foreign key that could
     * later be resolved to a different string.
     *
     * D5 — any existing PUBLISHED card for this student+term becomes SUPERSEDED and the new row takes the
     * next version. The old row is kept: the card handed to a parent existed, and a school that overwrites
     * it cannot answer "what did we send you in March?".
     */
    @Transactional
    public ReportCard publish(Long orgId, Long userId, String enrollNo, String studentName,
                              Term term, Long gradeId, String gradeName,
                              List<LineView> lines, Integer rank, Integer classSize, int[] attendance) {

        reportCardRepository.findCurrentScoped(enrollNo, term.getId(), ReportCardStatus.PUBLISHED, orgId, userId)
                .ifPresent(previous -> {
                    previous.setStatus(ReportCardStatus.SUPERSEDED);
                    previous.setUpdated(LocalDateTime.now());
                    reportCardRepository.save(previous);
                });

        Double termPercent = TermAggregator.weightedTermPercent(lines, weightsOf(lines, orgId, userId, term));
        GradeBand band = gradingService.bandFor(gradingService.scale(orgId, userId), termPercent);

        ReportCard card = ReportCard.builder()
                .studentEnrollNo(enrollNo)
                .studentName(studentName)
                .termId(term.getId())
                .termName(term.getName())
                .gradeId(gradeId)
                .gradeName(gradeName)
                .termPercent(termPercent)
                .termGradeName(band == null ? null : band.getName())
                .termGpa(TermAggregator.meanGpa(lines))
                .classRank(rank)
                .classSize(classSize)
                .attendancePresent(attendance == null ? null : attendance[0])
                .attendanceTotal(attendance == null ? null : attendance[1])
                .version(reportCardRepository.maxVersionScoped(enrollNo, term.getId(), orgId, userId) + 1)
                .status(ReportCardStatus.PUBLISHED)
                .issuedOn(LocalDate.now())
                .userId(userId)
                .organizationId(orgId)
                .dated(LocalDateTime.now())
                .updated(LocalDateTime.now())
                .build();
        card = reportCardRepository.save(card);

        List<ReportCardLine> rows = new ArrayList<>();
        for (LineView l : lines) {
            rows.add(ReportCardLine.builder()
                    .reportCardId(card.getId())
                    .examName(l.examName())
                    .subjectName(l.subjectName())
                    .maxMarks(l.maxMarks())
                    .marksObtained(l.marksObtained())
                    .absent(l.absent())
                    .percent(l.percent())
                    .gradeName(l.gradeName())
                    .gpaPoints(l.gpaPoints())
                    .sequence(l.sequence())
                    .build());
        }
        reportCardLineRepository.saveAll(rows);        // one batch, not a save per subject
        return card;
    }

    /** The weights for the exams these lines came from — read from the term, not from the client. */
    private Map<Long, Integer> weightsOf(List<LineView> lines, Long orgId, Long userId, Term term) {
        Map<Long, Integer> w = new HashMap<>();
        for (Exam e : examRepository.findByTermScoped(term.getId(), orgId, userId)) {
            w.put(e.getId(), e.getWeightPercent());
        }
        return w;
    }
}
