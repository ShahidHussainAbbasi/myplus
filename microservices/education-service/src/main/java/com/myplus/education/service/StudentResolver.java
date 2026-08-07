package com.myplus.education.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.settings.SettingsService;
import com.myplus.education.entity.GuardianPortalAccess;
import com.myplus.education.entity.PortalStatus;
import com.myplus.education.entity.PortalSubjectType;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.GuardianPortalAccessRepository;
import com.myplus.education.repository.StudentRepository;

/**
 * Slice 3.3 — <b>the ONLY source of a student's authority.</b>
 * Design: microservices/docs/slices/edu-3.3-student-portal.md (D2)
 *
 * <p>Deliberately mirrors {@link ChildResolver}, including what it refuses to do, because the two are the
 * complete set of places that decide whose record a portal session may read. Two files, one screen each,
 * reviewable together — the alternative that finding A of the education review already disproved is
 * scattering the rule across controllers.
 *
 * <h3>The one structural difference from a guardian: the set has exactly ONE member</h3>
 *
 * A guardian chooses between children, so {@code ChildResolver} takes a requested enrolment number and
 * intersects it. <b>A student has nothing to choose between</b>, so no endpoint here accepts an enrolment
 * number at all. That is stricter than validating one: a parameter that is always ignored cannot be the
 * subject of a validation bug, and it removes an IDOR surface instead of defending one.
 *
 * <h3>Derived per request, never cached</h3>
 *
 * Not in the JWT, not in a column, not in a session. A student who leaves, or whose access is revoked,
 * stops reading on the NEXT request. A stale copy of an access list is not a caching bug; it is someone
 * continuing to read a record they no longer hold.
 */
@Service
public class StudentResolver {

    /** 3.1's master switch. A school that has the portal off has it off for everyone. */
    public static final String PORTAL_ENABLED = ChildResolver.PORTAL_ENABLED;

    /**
     * 3.3's own switch. A school may run the guardian portal without the student one — and most will start
     * that way, because guardians are the audience they already hold addresses for.
     */
    public static final String STUDENTS_ENABLED = "edu.portal.students.enabled";

    @Autowired private GuardianPortalAccessRepository portalAccessRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SettingsService settingsService;

    /**
     * The signed-in student's access row, or null when this caller has no live student access.
     *
     * <p>Returns null — never throws — for every failure mode (portal off, students off, no access row,
     * revoked, unknown address), so a caller cannot distinguish "you are not a student here" from "this
     * school does not run the student portal". The controller answers all of them identically.
     *
     * <p><b>BOTH switches are checked, and the order does not matter because neither leaks.</b> 3.1's
     * {@code edu.portal.enabled} is the master; a school that turns the whole portal off must not keep
     * serving students because a second flag was left on.
     *
     * <p>A first successful sign-in flips {@code INVITED → ACTIVE}, so the school sees an invitation was
     * taken up without an activation step to build or forget — the same behaviour as a guardian's.
     */
    @Transactional
    public GuardianPortalAccess resolveStudent(Long orgId, String email) {
        if (orgId == null || email == null || email.isBlank()) return null;
        if (!settingsService.getBool(PORTAL_ENABLED)) return null;
        if (!settingsService.getBool(STUDENTS_ENABLED)) return null;

        GuardianPortalAccess access = portalAccessRepository
                .findLiveByEmailAndType(email.trim(), PortalSubjectType.STUDENT, PortalStatus.REVOKED, orgId)
                .orElse(null);
        if (access == null) return null;

        if (access.getStatus() == PortalStatus.INVITED) {
            access.setStatus(PortalStatus.ACTIVE);
            access.setActivatedOn(LocalDate.now());
            access.setUpdated(LocalDateTime.now());
            portalAccessRepository.save(access);
        }
        return access;
    }

    /**
     * The student record this access is about, derived now, or null.
     *
     * <p>Scoped by org as well as id: an access row is not proof the student is still in this tenant, and
     * an unscoped {@code findById} here is exactly the anti-IDOR failure the education review's finding A
     * catalogued in nine repositories.
     */
    @Transactional(readOnly = true)
    public Student myRecord(Long orgId, GuardianPortalAccess access) {
        if (orgId == null || access == null || access.getSubjectId() == null) return null;
        return studentRepository.findByIdForPortal(access.getSubjectId(), orgId).orElse(null);
    }

    /**
     * PURE. Is this enrolment number the caller's own?
     *
     * <p>Takes both sides as arguments so the rule is testable with no Spring, no database and no Docker —
     * the treatment given to {@code ClashDetector} (2.1), {@code LeaveBalanceCalculator} (2.3),
     * {@code HomeworkRules} (2.4) and {@code ChildResolver.isMine} (3.1).
     *
     * <p><b>No endpoint in 3.3 currently calls this</b>, because none accepts an enrolment number (D2). It
     * exists for the case that will eventually want one — a shared read reached from both portals — so that
     * the check is written, tested and obvious rather than improvised at that moment. Blank and null are
     * refused, never treated as "mine".
     */
    public static boolean isMe(String enrollNo, Collection<String> mine) {
        if (enrollNo == null || enrollNo.isBlank() || mine == null) return false;
        return mine.contains(enrollNo);
    }
}
