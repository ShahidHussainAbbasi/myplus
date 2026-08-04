package com.myplus.education.service;

import com.myplus.common.settings.SettingsService;
import com.myplus.education.entity.GuardianPortalAccess;
import com.myplus.education.entity.PortalStatus;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.GuardianPortalAccessRepository;
import com.myplus.education.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Slice 3.1 — <b>the ONLY source of a guardian's authority.</b>
 *
 * Design: microservices/docs/slices/edu-3.1-guardian-portal.md (D1)
 *
 * <p>Every read the guardian portal performs passes through here. If a future endpoint takes an enrolment
 * number and does not call {@link #requireMine}, it is a hole — that is the entire security model of this
 * slice, stated in one class so it can be reviewed in one place.
 *
 * <h3>Why the child set is derived per request, never cached</h3>
 *
 * Not in the JWT, not in a column, not in a session. A child transferring out, a guardian link being
 * corrected, or portal access being revoked must take effect on the <b>next request</b>. A stale copy of an
 * <i>access</i> list is not a caching bug; it is a stranger continuing to read a child's record.
 *
 * <h3>Why the intersection is a separate, pure function</h3>
 *
 * {@link #isMine(String, Collection)} takes both sides as arguments so the rule that decides whether a
 * guardian may see a child is testable with no Spring, no database and no Docker — the same treatment given
 * to {@code ClashDetector} (2.1), {@code LeaveBalanceCalculator} (2.3) and {@code HomeworkRules} (2.4),
 * applied to the check with the highest consequence in the programme.
 */
@Service
public class ChildResolver {

    /** D6 — a school opts in. A portal that goes live the moment code deploys is not a decision anyone made. */
    public static final String PORTAL_ENABLED = "edu.portal.enabled";

    @Autowired private GuardianPortalAccessRepository portalAccessRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SettingsService settingsService;

    /**
     * The signed-in guardian, or null when this caller has no live portal access.
     *
     * <p>Returns null — never throws — for every failure mode (portal disabled, no access row, revoked),
     * so a caller cannot accidentally distinguish "you are not a guardian" from "that school has the portal
     * off". The controller answers all of them identically.
     *
     * <p>A first successful sign-in flips {@code INVITED → ACTIVE}, which is how the school sees that an
     * invitation was taken up without a separate activation step to build or forget.
     */
    @Transactional
    public GuardianPortalAccess resolveGuardian(Long orgId, String email) {
        if (orgId == null || email == null || email.isBlank()) return null;
        if (!portalEnabled()) return null;

        GuardianPortalAccess access = portalAccessRepository
                .findLiveByEmail(email.trim(), PortalStatus.REVOKED, orgId).orElse(null);
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
     * The children of this guardian, derived now.
     *
     * <p>One query. A guardian with children at two branches of the same group sees both, because the
     * relationship — not the branch grant — is what defines the set.
     */
    @Transactional(readOnly = true)
    public List<Student> myChildren(Long orgId, Long guardianId) {
        if (orgId == null || guardianId == null) return List.of();
        return studentRepository.findByGuardianScoped(guardianId, orgId);
    }

    /** The enrolment numbers of {@link #myChildren}, for the intersection check. */
    @Transactional(readOnly = true)
    public Set<String> myEnrolNos(Long orgId, Long guardianId) {
        Set<String> out = new LinkedHashSet<>();
        for (Student s : myChildren(orgId, guardianId)) {
            if (s.getEnrollNo() != null && !s.getEnrollNo().isBlank()) out.add(s.getEnrollNo());
        }
        return out;
    }

    /**
     * <b>PURE.</b> Is this enrolment number one of mine?
     *
     * <p>Exact match, deliberately: no trimming beyond the caller's own, no case-folding, no prefix
     * matching. Enrolment numbers are opaque identifiers, and every loosening of this comparison is a way
     * for a crafted value to match a child it should not.
     */
    public static boolean isMine(String enrollNo, Collection<String> mine) {
        if (enrollNo == null || mine == null) return false;
        String wanted = enrollNo.trim();
        if (wanted.isEmpty()) return false;
        return mine.contains(wanted);
    }

    /**
     * The gate every portal read must pass: return the enrolment number if it is this guardian's, else null.
     *
     * <p>The caller answers null with <b>NOT_FOUND, never FORBIDDEN</b> — "that child exists but is not
     * yours" is itself a disclosure, and it is the difference between refusing access and confirming a
     * child's enrolment number to a stranger.
     */
    @Transactional(readOnly = true)
    public String requireMine(Long orgId, Long guardianId, String enrollNo) {
        Set<String> mine = myEnrolNos(orgId, guardianId);
        return isMine(enrollNo, mine) ? enrollNo.trim() : null;
    }

    /** Fails CLOSED: if the setting cannot be read, the portal is off. */
    public boolean portalEnabled() {
        try {
            return settingsService.getBool(PORTAL_ENABLED);
        } catch (Exception e) {
            return false;
        }
    }
}
