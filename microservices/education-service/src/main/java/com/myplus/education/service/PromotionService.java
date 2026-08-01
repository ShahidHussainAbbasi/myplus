package com.myplus.education.service;

import com.myplus.common.settings.SettingsService;
import com.myplus.education.entity.*;
import com.myplus.education.repository.*;
import com.myplus.education.service.PromotionPolicy.Proposal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Slice 1.6 — plan a promotion, then apply it.
 * Design: microservices/docs/slices/edu-1.6-promotion.md
 *
 * <p>Pattern: <b>dry-run command</b>. {@link #plan} computes and stores NOTHING; {@link #run} takes the
 * decisions the admin actually reviewed. The operation rewrites {@code gradeId} for a whole class, so
 * separating proposal from execution is what makes review possible — the same shape as 1.5's
 * preview → publish, for the same reason.
 *
 * <p>The rule itself lives in {@link PromotionPolicy} (a pure strategy object); this class owns only the
 * data access and the ordering of writes.
 */
@Service
public class PromotionService {

    /** D4 — the rule, per org. Defaults chosen so nothing retains a child by accident. */
    public static final String REQUIRE_PASS = "edu.promotion.requirePass";
    public static final String MIN_PERCENT = "edu.promotion.minPercent";
    public static final int MIN_PERCENT_DEFAULT = 33;

    @Autowired private PromotionRepository promotionRepository;
    @Autowired private ReportCardRepository reportCardRepository;
    @Autowired private TermRepository termRepository;
    @Autowired private GradeRepository gradeRepository;
    @Autowired private AcademicYearRepository academicYearRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private StudentVisibilityService studentVisibilityService;
    @Autowired private SettingsService settingsService;

    /** One row of the plan — what is PROPOSED, never what is stored. */
    public record PlanRow(String enrollNo, String name, Double yearPercent, String proposed,
                          String reason, boolean undecided, boolean alreadyDecided) { }

    /** One decision as reviewed by the admin. */
    public record Decision(String enrollNo, String outcome) { }

    /** Read the org's rule ONCE per batch rather than once per student. */
    public PromotionPolicy.Config policyConfig() {
        boolean requirePass;
        try { requirePass = settingsService.getBool(REQUIRE_PASS); } catch (Exception e) { requirePass = false; }
        int minPercent;
        try { minPercent = settingsService.getInt(MIN_PERCENT, MIN_PERCENT_DEFAULT); }
        catch (Exception e) { minPercent = MIN_PERCENT_DEFAULT; }
        return new PromotionPolicy.Config(requirePass, minPercent);
    }

    /**
     * The proposed outcome for every student in the source class.
     *
     * <p>D2 — the proposal reads PUBLISHED report cards, never live marks. Cards are loaded per TERM
     * (a handful of queries for the year) and indexed by student, rather than once per student.
     *
     * @param toGradeId null means "no target class" — the top of the school, so everyone graduates
     */
    @Transactional(readOnly = true)
    public List<PlanRow> plan(Long orgId, Long userId, Long yearId, Long fromGradeId, Long toGradeId) {
        List<Student> roster = new ArrayList<>();
        for (Student s : studentVisibilityService.visibleStudents(orgId, userId)) {
            if (s.getEnrollNo() == null || s.getEnrollNo().isBlank()) continue;
            if (fromGradeId != null && !fromGradeId.equals(s.getGradeId())) continue;
            roster.add(s);
        }
        if (roster.isEmpty()) return List.of();

        // Issued cards for every term of the year, indexed by student.
        Map<String, List<Double>> percentsByStudent = new HashMap<>();
        for (Term term : termRepository.findByYearScoped(yearId, orgId, userId)) {
            for (ReportCard c : reportCardRepository.findByTermScoped(term.getId(), orgId, userId)) {
                if (c.getStatus() != ReportCardStatus.PUBLISHED) continue;   // superseded/withdrawn do not count
                percentsByStudent.computeIfAbsent(c.getStudentEnrollNo(), k -> new ArrayList<>())
                        .add(c.getTermPercent());
            }
        }

        // Decisions already recorded for this year — the plan must not offer to make them twice (D6).
        List<String> enrollNos = roster.stream().map(Student::getEnrollNo).toList();
        Set<String> decided = new HashSet<>();
        for (Promotion p : promotionRepository.findByYearAndStudentsScoped(yearId, enrollNos, orgId, userId)) {
            if (p.getStatus() == PromotionStatus.APPLIED) decided.add(p.getStudentEnrollNo());
        }

        PromotionPolicy policy = new PromotionPolicy(policyConfig());
        boolean graduating = toGradeId == null;

        List<PlanRow> out = new ArrayList<>();
        for (Student s : roster) {
            List<Double> percents = percentsByStudent.getOrDefault(s.getEnrollNo(), List.of());
            Proposal p = policy.propose(percents, graduating);
            out.add(new PlanRow(
                    s.getEnrollNo(),
                    s.getName(),
                    averageOf(percents),
                    p.outcome() == null ? null : p.outcome().name(),
                    p.reason(),
                    p.undecided(),
                    decided.contains(s.getEnrollNo())));
        }
        return out;
    }

    private static Double averageOf(List<Double> percents) {
        List<Double> counted = percents.stream().filter(Objects::nonNull).toList();
        if (counted.isEmpty()) return null;
        double sum = counted.stream().mapToDouble(Double::doubleValue).sum();
        return Math.round((sum / counted.size()) * 10.0) / 10.0;
    }

    /** What {@link #run} did, so the caller can report it honestly rather than claiming success wholesale. */
    public record Result(int promoted, int retained, int graduated, int skipped, List<String> problems) { }

    /**
     * Apply the reviewed decisions.
     *
     * <p>Order matters and is deliberate: the decision is RECORDED first, then the student moves. If the
     * move fails the record is rolled back with it (one transaction); if the order were reversed, a
     * failure between the two would leave a child in a new class with nothing saying why.
     *
     * <p>A student already decided for this year is SKIPPED rather than moved again — the UNIQUE key would
     * refuse it anyway, and reporting it as skipped is more useful than surfacing a constraint violation.
     */
    @Transactional
    public Result run(Long orgId, Long userId, Long yearId, Long fromGradeId, Long toGradeId,
                      List<Decision> decisions) {
        AcademicYear year = academicYearRepository.findByIdScoped(yearId, orgId, userId).orElse(null);
        Grade from = fromGradeId == null ? null
                : gradeRepository.findByIdScoped(fromGradeId, orgId, userId).orElse(null);
        Grade to = toGradeId == null ? null
                : gradeRepository.findByIdScoped(toGradeId, orgId, userId).orElse(null);

        Map<String, Student> roster = new HashMap<>();
        for (Student s : studentVisibilityService.visibleStudents(orgId, userId)) {
            if (s.getEnrollNo() != null) roster.put(s.getEnrollNo(), s);
        }

        List<PlanRow> proposals = plan(orgId, userId, yearId, fromGradeId, toGradeId);
        Map<String, PlanRow> proposedByStudent = new HashMap<>();
        for (PlanRow r : proposals) proposedByStudent.put(r.enrollNo(), r);

        int promoted = 0, retained = 0, graduated = 0, skipped = 0;
        List<String> problems = new ArrayList<>();

        for (Decision d : decisions == null ? List.<Decision>of() : decisions) {
            String en = d.enrollNo() == null ? null : d.enrollNo().trim();
            if (en == null || en.isEmpty()) continue;

            Student student = roster.get(en);
            if (student == null) {
                // Out of the caller's branch, or not a student at all. Skipped silently in count, named
                // in problems — an error naming the student would confirm another campus's roster.
                skipped++;
                continue;
            }
            if (promotionRepository.findLiveScoped(en, yearId, PromotionStatus.APPLIED, orgId, userId).isPresent()) {
                skipped++;
                problems.add(en + ": already decided for this year");
                continue;
            }

            PromotionOutcome outcome;
            try {
                outcome = PromotionOutcome.valueOf(d.outcome());
            } catch (Exception e) {
                problems.add(en + ": unrecognised outcome");
                continue;
            }
            if (outcome == PromotionOutcome.PROMOTED && to == null) {
                problems.add(en + ": no target class was chosen, so they cannot be promoted");
                continue;
            }

            PlanRow proposal = proposedByStudent.get(en);
            boolean overridden = proposal != null && !Objects.equals(proposal.proposed(), outcome.name());
            String reason = overridden
                    ? "Decided by the school (proposal was "
                        + (proposal.proposed() == null ? "undecided" : proposal.proposed().toLowerCase()) + ")"
                    : (proposal == null ? null : proposal.reason());

            promotionRepository.save(Promotion.builder()
                    .studentEnrollNo(en)
                    .studentName(student.getName())
                    .fromGradeId(student.getGradeId())
                    .fromGradeName(from == null ? null : from.getName())
                    .toGradeId(outcome == PromotionOutcome.PROMOTED ? toGradeId : null)
                    .toGradeName(outcome == PromotionOutcome.PROMOTED && to != null ? to.getName() : null)
                    .academicYearId(yearId)
                    .academicYearName(year == null ? null : year.getName())
                    .outcome(outcome)
                    .status(PromotionStatus.APPLIED)
                    .reason(reason)
                    .overridden(overridden)
                    .userId(userId)
                    .organizationId(orgId)
                    .dated(LocalDateTime.now())
                    .updated(LocalDateTime.now())
                    .build());

            switch (outcome) {
                case PROMOTED -> {
                    student.setGradeId(toGradeId);
                    studentRepository.save(student);
                    promoted++;
                }
                case GRADUATED -> {
                    // Never deleted (D5) — a school is asked about its alumni for decades.
                    student.setStatus("Graduated");
                    studentRepository.save(student);
                    graduated++;
                }
                // A retention writes its row above and moves nothing: "we considered this child and kept
                // them back" and "we never got to this child" must remain distinguishable next year.
                case RETAINED -> retained++;
            }
        }
        return new Result(promoted, retained, graduated, skipped, problems);
    }

    /**
     * Undo one recorded decision: restore the class, mark the row REVERSED, keep it (D7).
     *
     * <p>Deleting the row would erase the fact that the batch ran, which is the one thing a school needs
     * when explaining what its records did.
     */
    @Transactional
    public String undo(Long orgId, Long userId, Long promotionId) {
        Promotion p = promotionRepository.findByIdScoped(promotionId, orgId, userId).orElse(null);
        if (p == null) return "Promotion not found";
        if (p.getStatus() != PromotionStatus.APPLIED) return "This promotion has already been reversed";

        Student student = studentRepository.findByOrganizationIdAndEnrollNo(orgId, p.getStudentEnrollNo())
                .orElse(null);
        if (student != null) {
            if (p.getOutcome() == PromotionOutcome.PROMOTED) {
                student.setGradeId(p.getFromGradeId());
                studentRepository.save(student);
            } else if (p.getOutcome() == PromotionOutcome.GRADUATED) {
                student.setStatus("Active");
                studentRepository.save(student);
            }
        }
        p.setStatus(PromotionStatus.REVERSED);
        p.setUpdated(LocalDateTime.now());
        promotionRepository.save(p);
        return null;
    }
}
