package com.myplus.education.service;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.education.entity.Guardian;
import com.myplus.education.entity.Notice;
import com.myplus.education.entity.NoticeAudience;
import com.myplus.education.entity.NoticeStatus;
import com.myplus.education.entity.PortalSubjectType;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.GuardianRepository;
import com.myplus.education.repository.StudentRepository;

/**
 * Slice 3.5 — <b>who a notice reaches.</b> Design: docs/slices/edu-3.5-notices.md (D2)
 *
 * <h3>The audience filter IS the authorisation (D5)</h3>
 *
 * Reading a notice is not privilege-gated — a guardian and a student are both ordinary authenticated
 * sessions. What decides whether a notice appears for them is {@link #reaches}, so <b>a bug in this method
 * is a disclosure</b>: a GUARDIANS-only notice about a family's fee arrears appearing in a student's portal,
 * or a class notice promoted to the whole school.
 *
 * <p>That is why it is a <b>pure static</b> taking every input as an argument — no Spring, no database, no
 * request — and why it is unit-tested before anything calls it. Same treatment as {@code ClashDetector}
 * (2.1), {@code LeaveBalanceCalculator} (2.3), {@code ChildResolver.isMine} (3.1) and
 * {@code StudentResolver.isMe} (3.3): the checks with the highest consequence are the ones that must be
 * readable in isolation.
 *
 * <h3>Derived, never stored</h3>
 *
 * {@link #recipients} runs against live enrolment every time. There is no {@code notice_recipient} table:
 * a stored list is a copy of an access decision that goes stale the moment a child transfers, and on a
 * safety notice a stale list is worse than anywhere else in this system (3.1 D1's reasoning, one slice on).
 */
@Service
public class NoticeAudienceResolver {

    @Autowired private StudentRepository studentRepository;
    @Autowired private GuardianRepository guardianRepository;

    /**
     * PURE. Does this notice reach a caller of this kind, in this class?
     *
     * @param audience     the notice's audience
     * @param noticeGrade  the notice's grade — meaningful only for {@link NoticeAudience#ONE_CLASS}
     * @param status       DRAFT reaches nobody, whatever the audience says
     * @param subjectType  who is asking: a GUARDIAN or a STUDENT
     * @param callerGrade  the class of the asker (a student's own; a guardian's child's)
     */
    public static boolean reaches(NoticeAudience audience, Long noticeGrade, NoticeStatus status,
                                  PortalSubjectType subjectType, Long callerGrade) {
        // A draft reaches nobody. Checked FIRST so no audience branch can ever be reached by an
        // unpublished notice — the one boundary this entity has (D1).
        if (status != NoticeStatus.PUBLISHED) return false;
        if (audience == null || subjectType == null) return false;

        switch (audience) {
            case WHOLE_SCHOOL:
                return true;
            case GUARDIANS:
                return subjectType == PortalSubjectType.GUARDIAN;
            case STUDENTS:
                return subjectType == PortalSubjectType.STUDENT;
            case ONE_CLASS:
                // BOTH sides must be known. A null notice grade would otherwise match every caller,
                // silently promoting a class notice to a whole-school one — the fail-OPEN direction, and
                // the case this method's unit test exists for. A caller with no class matches nothing.
                return noticeGrade != null && noticeGrade.equals(callerGrade);
            default:
                // An audience value this method has not been taught about reaches NOBODY. A new enum
                // constant must fail closed rather than default to "everyone" — which is what falling
                // through to `true` would do.
                return false;
        }
    }

    /** Convenience overload for a whole {@link Notice}. */
    public static boolean reaches(Notice n, PortalSubjectType subjectType, Long callerGrade) {
        if (n == null) return false;
        return reaches(n.getAudience(), n.getGradeId(), n.getStatus(), subjectType, callerGrade);
    }

    /**
     * The email addresses this notice should be delivered to, derived now.
     *
     * <p>Deduplicated: one guardian with three children at the school gets ONE copy of a whole-school
     * notice, not three. A `Set` is the whole mechanism — worth stating because the naive per-student loop
     * produces the opposite and looks correct.
     *
     * <p>Addresses are optional throughout this domain (D-7 recorded that students largely have none), so a
     * missing one is skipped silently rather than failing the publish. <b>The notice is still readable in
     * the portal by everyone the audience reaches</b> — which is exactly why finding C made the record, not
     * the email, the deliverable.
     */
    @Transactional(readOnly = true)
    public Set<String> recipients(Long orgId, Notice notice) {
        Set<String> out = new LinkedHashSet<>();
        if (orgId == null || notice == null) return out;

        boolean wantsGuardians = notice.getAudience() == NoticeAudience.WHOLE_SCHOOL
                || notice.getAudience() == NoticeAudience.GUARDIANS
                || notice.getAudience() == NoticeAudience.ONE_CLASS;
        boolean wantsStudents = notice.getAudience() == NoticeAudience.WHOLE_SCHOOL
                || notice.getAudience() == NoticeAudience.STUDENTS
                || notice.getAudience() == NoticeAudience.ONE_CLASS;

        // ONE_CLASS with no grade reaches nobody — the same rule as reaches(), stated once more here
        // because this path sends email and the two must not be able to disagree.
        if (notice.getAudience() == NoticeAudience.ONE_CLASS && notice.getGradeId() == null) return out;

        // One scan of the roster, in the tenant, rather than a query per audience branch. The class filter
        // is applied in the same pass.
        Set<Long> guardianIds = new LinkedHashSet<>();
        for (Student s : studentRepository.findScoped(orgId, null)) {
            if (notice.getAudience() == NoticeAudience.ONE_CLASS
                    && !notice.getGradeId().equals(s.getGradeId())) {
                continue;
            }
            if (wantsStudents && sendable(s.getEmail())) out.add(s.getEmail().trim());
            if (wantsGuardians && s.getGuardianId() != null) guardianIds.add(s.getGuardianId());
        }

        if (wantsGuardians && !guardianIds.isEmpty()) {
            for (Guardian g : guardianRepository.findScoped(orgId, null)) {
                if (guardianIds.contains(g.getId()) && sendable(g.getEmail())) out.add(g.getEmail().trim());
            }
        }
        return out;
    }

    /** An address worth attempting: present, and shaped like one. */
    private static boolean sendable(String email) {
        return email != null && !email.isBlank() && email.contains("@");
    }
}
