package com.myplus.education.service;

import com.myplus.education.entity.*;
import com.myplus.education.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * The ONE place a staff absence is opened or cleared.
 *
 * <p><b>Extracted during slice 2.3, and it is the point of the slice.</b> Three different actions now mean
 * "this teacher is not in on this day":
 *
 * <pre>
 *   register marks ABSENT / LEAVE   ─┐
 *   a leave request is APPROVED     ─┼─→ StaffAbsence ──→ 2.2 opens the cover list
 *   (2.2's own manual "mark absent")─┘
 * </pre>
 *
 * and three mean the opposite (corrected to PRESENT, leave cancelled/rejected, "they came in after all").
 * If any one of those six paths writes its own version, a teacher ends up on approved leave with no cover
 * arranged and <b>nothing looks wrong</b>. So the cascade lives here and every caller delegates —
 * the same extraction {@code StudentVisibilityService} got in 1.5, for the same reason: duplicated rules
 * drift silently, and this one drifts into an unsupervised classroom.
 *
 * <p>2.2's design reserved {@code StaffAbsence.leaveId} for exactly this link; 2.3 is what fills it.
 */
@Service
public class StaffAbsenceService {

    @Autowired private StaffAbsenceRepository staffAbsenceRepository;
    @Autowired private SubstitutionRepository substitutionRepository;
    @Autowired private TimetableEntryRepository timetableEntryRepository;
    @Autowired private TermRepository termRepository;

    /**
     * Record that a teacher is out, and open their lessons that day as UNCOVERED.
     *
     * <p>Idempotent: marking the same day twice is a double-click, not an error — and the UNIQUE key on
     * {@code (org, staff_id, absence_date)} is what guarantees one row under a real race.
     *
     * @param leaveId the approved request behind this absence, or null when a register/manual entry
     * @return how many lessons now need cover
     */
    @Transactional
    public int openAbsence(Long orgId, Long userId, Long staffId, String staffName,
                           LocalDate date, String reason, Long leaveId) {
        StaffAbsence existing = staffAbsenceRepository.findOneScoped(staffId, date, orgId, userId).orElse(null);
        if (existing == null) {
            staffAbsenceRepository.save(StaffAbsence.builder()
                    .staffId(staffId).staffName(staffName).absenceDate(date)
                    .reason(reason).leaveId(leaveId)
                    .userId(userId).organizationId(orgId)
                    .dated(LocalDateTime.now()).updated(LocalDateTime.now())
                    .build());
        } else if (leaveId != null && existing.getLeaveId() == null) {
            // The register marked them absent first and the leave was approved afterwards. Link it rather
            // than writing a second row: the absence is the same fact, now with an authorisation behind it.
            existing.setLeaveId(leaveId);
            if (reason != null) existing.setReason(reason);
            existing.setUpdated(LocalDateTime.now());
            staffAbsenceRepository.save(existing);
        }
        return openUncoveredLessons(orgId, userId, staffId, date);
    }

    /**
     * The teacher is in after all — remove the absence and CANCEL its substitutions.
     *
     * <p>The absence is a fact that turned out to be false, so it goes. The substitutions are decisions the
     * school acted on, so they are kept as {@code CANCELLED} rather than deleted — the rule 1.5 D5 and
     * 1.6 D7 established for anything a person acted upon.
     *
     * @return how many substitutions were cancelled
     */
    @Transactional
    public int clearAbsence(Long orgId, Long userId, StaffAbsence absence) {
        int cancelled = 0;
        for (Substitution s : substitutionRepository.findByDateScoped(absence.getAbsenceDate(), orgId, userId)) {
            if (!Objects.equals(s.getAbsentStaffId(), absence.getStaffId())) continue;
            if (s.getStatus() == SubstitutionStatus.CANCELLED) continue;
            s.setStatus(SubstitutionStatus.CANCELLED);
            s.setUpdated(LocalDateTime.now());
            substitutionRepository.save(s);
            cancelled++;
        }
        staffAbsenceRepository.delete(absence);
        return cancelled;
    }

    /** Clear by (staff, date) when the caller has no absence row in hand — the register-correction path. */
    @Transactional
    public int clearAbsenceFor(Long orgId, Long userId, Long staffId, LocalDate date) {
        StaffAbsence absence = staffAbsenceRepository.findOneScoped(staffId, date, orgId, userId).orElse(null);
        return absence == null ? 0 : clearAbsence(orgId, userId, absence);
    }

    /**
     * Write an UNCOVERED row per lesson the absent teacher was due to take that day.
     *
     * <p>2.2 D5 — an uncovered lesson is a first-class state, not a missing row: a class about to be
     * unsupervised must be visible to every query and countable at term end.
     *
     * <p>Scans the term-less bucket AND every term, because a lesson can sit in either (1.1's nullable
     * term) and checking only one would silently leave lessons uncovered.
     */
    private int openUncoveredLessons(Long orgId, Long userId, Long staffId, LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        java.util.List<Long> buckets = new java.util.ArrayList<>();
        buckets.add(null);
        for (Term t : termRepository.findScoped(orgId, userId)) buckets.add(t.getId());

        int opened = 0;
        for (Long termId : buckets) {
            for (TimetableEntry e : timetableEntryRepository.findByTermScoped(termId, orgId, userId)) {
                if (!staffId.equals(e.getStaffId()) || e.getDayOfWeek() != day) continue;
                if (substitutionRepository.findOneScoped(e.getId(), date, orgId, userId).isPresent()) continue;
                substitutionRepository.save(Substitution.builder()
                        .timetableEntryId(e.getId()).subDate(date)
                        .absentStaffId(staffId).status(SubstitutionStatus.UNCOVERED)
                        .userId(userId).organizationId(orgId)
                        .dated(LocalDateTime.now()).updated(LocalDateTime.now())
                        .build());
                opened++;
            }
        }
        return opened;
    }
}
