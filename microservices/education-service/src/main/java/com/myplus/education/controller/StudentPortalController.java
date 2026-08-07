package com.myplus.education.controller;

import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.GuardianPortalAccess;
import com.myplus.education.entity.PortalSubjectType;
import com.myplus.education.entity.Student;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.service.PortalReadService;
import com.myplus.education.service.StudentResolver;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 3.3 — the student's own view of their own record.
 * Design: microservices/docs/slices/edu-3.3-student-portal.md
 *
 * <h3>Every mapping is {@code /portal/my/**}, and that prefix is doing real work</h3>
 *
 * It sits inside education's existing {@code myplus.portal.allowlist=/portal/**}, so this controller needed
 * no change to the deny rule — which is 3.1b D6's claim ("built generically so 3.3 adds a resolver, not a
 * mechanism") being tested by a second audience rather than asserted.
 *
 * <p>The {@code /my/} segment is not decoration: it says at the URL that these reads take no subject, so a
 * future endpoint that DOES take one cannot be added here by habit.
 *
 * <h3>No enrolment number is accepted anywhere in this class</h3>
 *
 * A student's set has exactly one member (D2), so there is nothing to choose between. Reading a parameter
 * and then validating it would create an IDOR surface that has no reason to exist; not reading one removes
 * the question. <b>Passing {@code ?enrollNo=} to any endpoint here changes nothing</b>, and the gate
 * asserts exactly that.
 *
 * <h3>Read-only, and narrower than the guardian portal on purpose</h3>
 *
 * There is no write endpoint. There is deliberately no {@code /portal/my/dues} and no behaviour-notes
 * endpoint (D4): a family's financial position is the guardian's business, and 2.5's notes were written by
 * staff with no expectation that the child they are about would read them. Both omissions are policy, and
 * both are gated so a later change has to be deliberate.
 */
@Controller
public class StudentPortalController {

    @Autowired private StudentResolver studentResolver;
    @Autowired private PortalReadService portalReadService;
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
     * The signed-in student, or null.
     *
     * <p>One shared entry point so no endpoint can forget the portal-enabled check, the students-enabled
     * check, the revoked check or the address lookup. Every method below starts here.
     */
    private Student me() {
        GuardianPortalAccess access = studentResolver.resolveStudent(orgId(), email());
        if (access == null) return null;
        return studentResolver.myRecord(orgId(), access);
    }

    /** The single refusal used for every unauthorised case — NOT_FOUND, never FORBIDDEN (3.1 D2 / 3.1b D4). */
    private GenericResponse notYours() {
        return new GenericResponse("NOT_FOUND", "Not found");
    }

    // ── who am I ────────────────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/portal/my/me", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse me_() {
        try {
            Student s = me();
            if (s == null) return notYours();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", s.getName());
            out.put("enrollNo", s.getEnrollNo());
            // No guardian details, no address, no fee figures: this is an identity echo for the header,
            // not a record dump.
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── my record ───────────────────────────────────────────────────────────────────────────────

    /**
     * My week. The most-used screen in any student portal, and new in this slice.
     *
     * <p>{@code termId} is left null so 2.1's query resolves the entries the school actually maintains; a
     * term-less tenant still gets a timetable rather than an empty page, which is 1.1's "a null term is
     * permanently valid" rule reaching its fourth consumer.
     */
    @RequestMapping(value = "/portal/my/timetable", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse timetable() {
        try {
            Student s = me();
            if (s == null) return notYours();
            List<Map<String, Object>> out = portalReadService.timetable(orgId(), s.getGradeId(), null);
            audit("PORTAL_READ_TIMETABLE", s.getEnrollNo());
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** My results — PUBLISHED report cards only, exactly as a guardian sees them (one renderer). */
    @RequestMapping(value = "/portal/my/results", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse results() {
        try {
            Student s = me();
            if (s == null) return notYours();
            List<Map<String, Object>> out = portalReadService.results(orgId(), s.getEnrollNo());
            audit("PORTAL_READ_RESULTS", s.getEnrollNo());
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** My homework, and what has been recorded for me. */
    @RequestMapping(value = "/portal/my/homework", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse homework() {
        try {
            Student s = me();
            if (s == null) return notYours();
            List<Map<String, Object>> out = portalReadService.homework(orgId(), s);
            audit("PORTAL_READ_HOMEWORK", s.getEnrollNo());
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** My attendance summary. */
    @RequestMapping(value = "/portal/my/attendance", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse attendance() {
        try {
            Student s = me();
            if (s == null) return notYours();
            Map<String, Object> out = portalReadService.attendance(orgId(), s.getEnrollNo());
            audit("PORTAL_READ_ATTENDANCE", s.getEnrollNo());
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Slice 3.5 — school notices addressed to me or to everyone.
     *
     * <p>No audit call, unlike every read above, and that is deliberate: those return one student's private
     * record, while this returns what the school has told the whole community. Auditing a notice read would
     * add a row per family per notice for no investigative value.
     */
    @RequestMapping(value = "/portal/my/notices", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse notices() {
        try {
            Student s = me();
            if (s == null) return notYours();
            List<Map<String, Object>> out =
                    portalReadService.notices(orgId(), PortalSubjectType.STUDENT, s.getGradeId());
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * A student reading their own record is still an external principal reading a student record.
     *
     * <p>Audited on the same terms as a guardian's read (3.1): best-effort, because a failed audit must not
     * deny a student their own results, and the read has already been authorised by this point.
     */
    private void audit(String action, String enrollNo) {
        try {
            auditService.record(action, "Student", enrollNo, "student portal read by " + email());
        } catch (Exception e) {
            appUtil.le(getClass(), e);
        }
    }
}
