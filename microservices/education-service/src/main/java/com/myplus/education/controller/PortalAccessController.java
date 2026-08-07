package com.myplus.education.controller;

import java.time.LocalDate;
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

import com.myplus.commerce.contracts.client.AuthAccountClient;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.*;
import com.myplus.education.repository.*;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 3.1 — the SCHOOL side of the guardian portal: who is granted access, and who has it withdrawn.
 * Design: microservices/docs/slices/edu-3.1-guardian-portal.md
 *
 * <p>Separate from {@link GuardianPortalController} on purpose. That one is the outsider's surface and is
 * read-only; this one is staff-facing and is the only place access is granted. Keeping them apart means a
 * guardian session can never reach an endpoint that changes access, however the roles evolve.
 *
 * <p><b>{@code ADMIN_PRIVILEGE}</b> — granting an outsider sight of a child's record is a policy act, the
 * same tier as fee settings and report-card publication, not day-to-day teacher work.
 */
@Controller
public class PortalAccessController {

    /**
     * Slice 3.1b. Optional so education-service still starts in a deployment where auth-service is not
     * wired — inviting then reports that the sign-in could not be created, rather than the whole service
     * failing to come up over a feature most of its screens do not use.
     */
    @Autowired(required = false) private AuthAccountClient authAccountClient;

    @Autowired private GuardianPortalAccessRepository portalAccessRepository;
    @Autowired private GuardianRepository guardianRepository;
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

    private static Long parseLong(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return Long.valueOf(s.trim()); } catch (Exception e) { return null; }
    }

    /** Who has portal access, including revoked rows — they are kept, never deleted. */
    @RequestMapping(value = "/getPortalAccess", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getPortalAccess() {
        try {
            Long org = orgId(), uid = userId();
            List<Map<String, Object>> out = new ArrayList<>();
            for (GuardianPortalAccess a : portalAccessRepository.findScoped(org, uid)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", a.getId());
                m.put("guardianId", a.getGuardianId());
                // 3.3 — WHO this row is about. The screen needs it to keep the two tabs apart, and a
                // caller that ignores it still behaves as before, because every pre-3.3 row is a GUARDIAN.
                m.put("subjectType", a.getSubjectType() == null ? null : a.getSubjectType().name());
                m.put("subjectId", a.getSubjectId());
                m.put("guardianName", a.getGuardianName());
                m.put("email", a.getEmail());
                m.put("status", a.getStatus() == null ? null : a.getStatus().name());
                m.put("invitedOn", a.getInvitedOn() == null ? null : a.getInvitedOn().toString());
                m.put("activatedOn", a.getActivatedOn() == null ? null : a.getActivatedOn().toString());
                // How many children this access would actually reach — the number that makes the
                // consequence of granting it legible, rather than a name and an email in a list.
                // A student's access reaches exactly one record — their own — so there is nothing to count.
                m.put("childCount", a.getSubjectType() == PortalSubjectType.STUDENT ? 1
                        : studentRepository.findByGuardianScoped(a.getGuardianId(), org).size());
                out.add(m);
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Invite a guardian.
     *
     * <p>D3 — invitation only. There is no endpoint anywhere that lets a guardian claim a child by typing an
     * enrolment number; the school, which already knows who the guardians are, grants access explicitly.
     *
     * <p>The email is taken from the GUARDIAN RECORD, never from the request. Accepting an email here would
     * let a staff member point a child's portal at any address they liked, with no trace in the guardian
     * record that they had done so.
     */
    @RequestMapping(value = "/invitePortalAccess", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse invitePortalAccess(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();

            // Slice 3.3 — the same endpoint now grants access to a STUDENT as well. Absent or unrecognised
            // means GUARDIAN, so every existing caller (the 3.1 screen, 3.1's gate, the guardian tab) keeps
            // its exact behaviour without being touched.
            String subject = request.getParameter("subjectType");
            if (PortalSubjectType.STUDENT.name().equalsIgnoreCase(subject == null ? null : subject.trim())) {
                return inviteStudent(request, org, uid);
            }

            Long guardianId = parseLong(request.getParameter("guardianId"));
            if (guardianId == null) return new GenericResponse("ERROR", "Guardian is required");

            Guardian guardian = guardianRepository.findByIdScoped(guardianId, org, uid).orElse(null);
            if (guardian == null) return new GenericResponse("NOT_FOUND", "Guardian not found");
            if (!StringUtils.hasText(guardian.getEmail())) {
                return new GenericResponse("FAILED",
                        "This guardian has no email address on record. Add one before inviting them.");
            }

            GuardianPortalAccess access = portalAccessRepository
                    .findByGuardianScoped(guardianId, org).orElse(null);
            if (access == null) {
                access = GuardianPortalAccess.builder()
                        .guardianId(guardianId)
                        // 3.3 — stamped on the way in, so the generalised unique key covers new rows too.
                        // V25 backfilled the old ones; between them every row has a subject.
                        .subjectType(PortalSubjectType.GUARDIAN).subjectId(guardianId)
                        .userId(uid).organizationId(org).dated(LocalDateTime.now())
                        .build();
            } else if (access.getStatus() == PortalStatus.ACTIVE) {
                return new GenericResponse("SUCCESS",
                        guardian.getName() + " already has portal access");
            }
            // Re-inviting is the EXPLICIT act that moves access to a corrected email — the snapshot is
            // never silently refreshed from the guardian record (see the entity javadoc).
            access.setEmail(guardian.getEmail().trim());
            access.setGuardianName(guardian.getName());
            access.setStatus(PortalStatus.INVITED);
            access.setInvitedOn(LocalDate.now());
            access.setRevokedOn(null);
            access.setUpdated(LocalDateTime.now());
            portalAccessRepository.save(access);

            int children = studentRepository.findByGuardianScoped(guardianId, org).size();

            // Slice 3.1b — the invitation now creates the SIGN-IN ACCOUNT, not just the access row.
            // auth-service sends the set-password email, and that email is what verifies the address:
            // until the token is used nobody can sign in, so a typo'd address simply never becomes an
            // account (3.1 §6's carried requirement). Failure is surfaced, NOT swallowed: an access row
            // whose account was never created is a guardian who will never be able to log in, and the
            // school must find that out now rather than when the family calls.
            String accountProblem = provisionAccount(access.getEmail(), org);

            auditService.record("PORTAL_ACCESS_INVITED", "Guardian", String.valueOf(guardianId),
                    "email=" + access.getEmail() + " children=" + children
                            + (accountProblem == null ? " account=ok" : " account=FAILED"));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", access.getId());
            out.put("childCount", children);
            out.put("account", accountProblem == null ? "OK" : "FAILED");
            // The count is surfaced because "you have just given this address sight of 2 children's
            // records" is the fact the person clicking needs, and it is invisible otherwise.
            String msg = guardian.getName() + " invited — this grants sight of " + children + " child(ren)";
            if (accountProblem != null) {
                // The access row stands, so a retry is just "invite again" — but the school is told plainly
                // that no sign-in exists yet, rather than being left to assume the guardian can log in.
                return new GenericResponse("PARTIAL", msg
                        + ". The sign-in could NOT be created, so they cannot log in yet — try inviting "
                        + "again. (" + accountProblem + ")", out);
            }
            return new GenericResponse("SUCCESS", msg + ", and a set-password email has been sent", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Withdraw access. The row is kept as REVOKED, never deleted.
     *
     * <p>"This person used to be able to read this child's record" is exactly what an investigation needs,
     * and deleting the row is how a school loses the ability to answer it — the same rule as a superseded
     * report card (1.5 D5) and a reversed promotion (1.6 D7).
     */
    @RequestMapping(value = "/revokePortalAccess", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse revokePortalAccess(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long id = parseLong(request.getParameter("id"));
            if (id == null) return new GenericResponse("ERROR", "Access record is required");

            GuardianPortalAccess access = portalAccessRepository.findByIdScoped(id, org, uid).orElse(null);
            if (access == null) return new GenericResponse("NOT_FOUND", "Access record not found");
            if (access.getStatus() == PortalStatus.REVOKED) {
                return new GenericResponse("SUCCESS", "Access is already revoked");
            }
            access.setStatus(PortalStatus.REVOKED);
            access.setRevokedOn(LocalDate.now());
            access.setUpdated(LocalDateTime.now());
            portalAccessRepository.save(access);

            // Slice 3.1b — withdraw the SIGN-IN too, not just the access row. Leaving the account enabled
            // would let a revoked guardian keep logging in: 3.1's ChildResolver would refuse them every
            // read, so they would see nothing — but "can still authenticate" is not what a school means by
            // revoked, and it is the wrong thing to have to explain afterwards.
            // Best-effort ON PURPOSE, and the opposite choice to invite: the access row is ALREADY revoked
            // by the time this runs, so the guardian can read nothing regardless. Failing the whole
            // operation because auth-service was slow would leave the school unable to revoke at all.
            String accountProblem = withdrawAccount(access.getEmail());

            // 3.3 — the trail must name the right KIND of person. This read "Guardian" +
            // getGuardianId(), which for a student row is a null id under the wrong entity name — an audit
            // entry that cannot be traced back to anyone, which is worse than none.
            String subjectName = access.getSubjectType() == PortalSubjectType.STUDENT ? "Student" : "Guardian";
            Long subjectRef = access.getSubjectId() != null ? access.getSubjectId() : access.getGuardianId();
            auditService.record("PORTAL_ACCESS_REVOKED", subjectName,
                    String.valueOf(subjectRef), "email=" + access.getEmail()
                            + (accountProblem == null ? " signIn=disabled" : " signIn=STILL_ENABLED"));
            // Takes effect on the guardian's NEXT request: nothing about their access is cached (D1).
            if (accountProblem != null) {
                return new GenericResponse("SUCCESS", "Portal access revoked — they can no longer see "
                        + "anything. Their sign-in could not be disabled just now and will be retried; "
                        + "it grants no access on its own.");
            }
            return new GenericResponse("SUCCESS", "Portal access revoked");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Slice 3.3 — invite a STUDENT. Same flow, same table, same deny rule; two extra refusals.
     *
     * <p>Kept as its own method rather than threaded through the guardian path with conditionals: the two
     * differ in which record supplies the address and in what makes an invitation impossible, and a shared
     * body with four branches is how one audience's rule silently starts applying to the other.
     */
    private GenericResponse inviteStudent(HttpServletRequest request, Long org, Long uid) {
        Long studentId = parseLong(request.getParameter("studentId"));
        if (studentId == null) return new GenericResponse("ERROR", "Student is required");

        Student student = studentRepository.findByIdForPortal(studentId, org).orElse(null);
        if (student == null) return new GenericResponse("NOT_FOUND", "Student not found");

        // D5 — the address comes from the STUDENT RECORD, never the request. Accepting one here would let a
        // staff member point a child's portal at any address they liked, with nothing in the record to show
        // they had done so. Same rule, same reason, as 3.1 D3 for guardians.
        String email = student.getEmail() == null ? "" : student.getEmail().trim();
        if (!StringUtils.hasText(email)) {
            // The BOUNDARY of D-7, surfaced rather than worked around. If a school hits this at scale, that
            // is the trigger for school-issued join codes (D-6 option C) — it is not a bug to route around.
            return new GenericResponse("FAILED",
                    "This student has no email address on record. Add one before inviting them.");
        }

        // D6 — THE SEVERE CASE, and it is silent without this check. auth-service keys a User by email and
        // createOrLinkPortalUser deliberately LINKS an existing address rather than refusing, because one
        // adult may be a guardian at two schools. That is right for one person with two roles and wrong for
        // two different people: a student record carrying their guardian's address would resolve to the
        // GUARDIAN'S LOGIN, handing a child their guardian's session. auth-service cannot tell the
        // difference — only education, holding both records, can. So the check lives here.
        List<Guardian> sharers = guardianRepository.findAllByEmailScoped(email, org);
        if (!sharers.isEmpty()) {
            Guardian sharing = sharers.get(0);
            return new GenericResponse("FAILED",
                    "That address (" + email + ") already belongs to guardian " + sharing.getName()
                            + ". A student needs their own address — sharing one would sign the student "
                            + "in as the guardian.");
        }

        GuardianPortalAccess access = portalAccessRepository
                .findBySubjectScoped(PortalSubjectType.STUDENT, studentId, org).orElse(null);
        if (access == null) {
            // guardianId is left NULL on purpose — see the entity javadoc and V25's header.
            access = GuardianPortalAccess.builder()
                    .subjectType(PortalSubjectType.STUDENT).subjectId(studentId)
                    .userId(uid).organizationId(org).dated(LocalDateTime.now())
                    .build();
        } else if (access.getStatus() == PortalStatus.ACTIVE) {
            return new GenericResponse("SUCCESS", student.getName() + " already has portal access");
        }
        access.setEmail(email);
        access.setGuardianName(student.getName());   // the display name of the SUBJECT — see V25's header
        access.setStatus(PortalStatus.INVITED);
        access.setInvitedOn(LocalDate.now());
        access.setRevokedOn(null);
        access.setUpdated(LocalDateTime.now());
        portalAccessRepository.save(access);

        String accountProblem = provisionAccount(email, org, "STUDENT");

        auditService.record("PORTAL_ACCESS_INVITED", "Student", String.valueOf(studentId),
                "email=" + email + (accountProblem == null ? " account=ok" : " account=FAILED"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", access.getId());
        out.put("childCount", 1);            // a student's set has exactly one member, by definition (D2)
        out.put("account", accountProblem == null ? "OK" : "FAILED");
        String msg = student.getName() + " invited — they will see their own record only";
        if (accountProblem != null) {
            return new GenericResponse("PARTIAL", msg
                    + ". The sign-in could NOT be created, so they cannot log in yet — try inviting "
                    + "again. (" + accountProblem + ")", out);
        }
        return new GenericResponse("SUCCESS", msg + ", and a set-password email has been sent", out);
    }

    // ── Slice 3.1b: the sign-in account behind the access row ─────────────────────────────────────

    /**
     * Create or link the guardian's login. Returns null on success, else a short reason.
     *
     * <p>Returns a reason rather than throwing so the caller can report a PARTIAL outcome: the access row
     * is already committed, and the school needs to know the difference between "invited" and "invited but
     * they cannot actually log in yet".
     *
     * <p>Not queued through the outbox: the school is standing at the screen, and an invitation that will
     * be created in thirty seconds is indistinguishable to them from one that failed. The set-password
     * EMAIL is auth-service's to send and retry.
     */
    private String provisionAccount(String email, Long orgId) {
        return provisionAccount(email, orgId, "GUARDIAN");
    }

    /**
     * Slice 3.3 — the same call for either audience; only the membership role differs.
     *
     * <p>{@code role} decides which auth-service role and membership the account gets, and therefore which
     * confined role travels in the JWT. Getting it wrong does not open the staff surface — an unrecognised
     * role is not in {@code confined-roles}, so it would be UNCONFINED. That is the fail-open direction,
     * which is why the value is a constant at each call site and never taken from the request.
     */
    private String provisionAccount(String email, Long orgId, String role) {
        if (authAccountClient == null) return "account service unavailable";
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("email", email);
            req.put("organizationId", orgId);
            req.put("role", role);
            authAccountClient.createOrLink(req);
            return null;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            String m = e.getMessage();
            return (m == null || m.length() > 120) ? "account service error" : m;
        }
    }

    /** Disable the guardian's login. Returns null on success, else a short reason. Best-effort — see caller. */
    private String withdrawAccount(String email) {
        if (authAccountClient == null) return "account service unavailable";
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("email", email);
            authAccountClient.disable(req);
            return null;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return "account service error";
        }
    }
}
