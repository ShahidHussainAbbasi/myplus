package com.myplus.education.controller;

import java.util.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.*;
import com.myplus.education.repository.*;
import com.myplus.education.service.ChildResolver;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.service.MeetingService;
import com.myplus.education.service.PortalReadService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 3.1 — the guardian portal. <b>The first surface an outsider can reach.</b>
 * Design: microservices/docs/slices/edu-3.1-guardian-portal.md
 *
 * <h3>Why this is a separate controller and not a privilege on the existing ones (D2)</h3>
 *
 * The cheap option was to add a {@code GUARDIAN} role and {@code @PreAuthorize} the existing endpoints.
 * That makes every one of ~31 education controllers a place a guardian might reach, and relies on each of
 * them remembering to narrow an org-scoped query to two children.
 *
 * <b>The education review's finding A already proved that bet loses:</b> a scoping rule that had to be
 * remembered per controller was forgotten in seven of them. Repeating it with an external principal is
 * materially worse.
 *
 * <p>So this is an <b>allowlist</b>: the portal's attack surface is exactly what is written below, and a
 * new staff endpoint is not automatically a guardian endpoint. A guardian hitting a staff URL is refused
 * because it is not part of this surface — not because a role string happened not to match.
 *
 * <h3>Every read passes through {@link ChildResolver}</h3>
 *
 * No method here trusts a client-supplied enrolment number. Each intersects it with the set derived from
 * {@code Student.guardianId} on this request (D1), and answers {@code NOT_FOUND} — never {@code FORBIDDEN} —
 * when it does not belong, because "that child exists but is not yours" is itself a disclosure.
 *
 * <h3>Read-only</h3>
 *
 * Every mapping is GET. There is no write endpoint on the portal surface at all (D4).
 */
@Controller
public class GuardianPortalController {

    @Autowired private ChildResolver childResolver;
    /**
     * Slice 3.3 — the reads moved DOWN here so the student portal shares them rather than copying them
     * (3.3 finding B). This controller kept the only thing that is actually guardian-specific: deciding
     * WHICH child the caller may see. The renderer never makes that decision.
     */
    @Autowired private PortalReadService portalReadService;
    /** Slice edu-3.4 — meetings, delivered on the shared scheduling core (SCHED-1). */
    @Autowired private MeetingService meetingService;
    @Autowired private GradeRepository gradeRepository;
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
     * The signed-in guardian, or null.
     *
     * <p>One shared entry point so no endpoint can forget the portal-enabled check, the revoked check, or
     * the email lookup. Every method below starts here.
     */
    private GuardianPortalAccess guardian() {
        return childResolver.resolveGuardian(orgId(), email());
    }

    /** The single refusal used for every unauthorised case — see the class javadoc on NOT_FOUND. */
    private GenericResponse notYours() {
        return new GenericResponse("NOT_FOUND", "Not found");
    }

    // ── who am I ────────────────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/portal/me", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse me() {
        try {
            GuardianPortalAccess g = guardian();
            if (g == null) return notYours();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("guardianName", g.getGuardianName());
            out.put("email", g.getEmail());
            out.put("status", g.getStatus() == null ? null : g.getStatus().name());
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** My children — the set everything else is filtered by. Derived now, never cached (D1). */
    @RequestMapping(value = "/portal/children", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse children() {
        try {
            GuardianPortalAccess g = guardian();
            if (g == null) return notYours();

            Map<Long, String> gradeNames = new HashMap<>();
            for (Grade gr : gradeRepository.findScoped(orgId(), null)) {
                gradeNames.put(gr.getId(), gradeLabel(gr));
            }

            List<Map<String, Object>> out = new ArrayList<>();
            for (Student s : childResolver.myChildren(orgId(), g.getGuardianId())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("enrollNo", s.getEnrollNo());
                m.put("name", s.getName());
                m.put("gradeName", gradeNames.get(s.getGradeId()));
                out.add(m);
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── one child's data — each intersected with the derived set ────────────────────────────────

    /**
     * Results: <b>PUBLISHED report cards only</b>.
     *
     * <p>1.5 made an issued card a snapshot precisely so it could be shown outside the school. A DRAFT or
     * SUPERSEDED card must never appear here — a guardian seeing a mark that later changes is exactly the
     * harm snapshotting prevents.
     */
    @RequestMapping(value = "/portal/results", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse results(final HttpServletRequest request) {
        try {
            String enrollNo = mineOrNull(request);
            if (enrollNo == null) return notYours();
            List<Map<String, Object>> out = portalReadService.results(orgId(), enrollNo);
            audit("PORTAL_READ_RESULTS", enrollNo);
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Attendance: a present/total summary plus the recent days, for one child. */
    @RequestMapping(value = "/portal/attendance", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse attendance(final HttpServletRequest request) {
        try {
            String enrollNo = mineOrNull(request);
            if (enrollNo == null) return notYours();
            Map<String, Object> out = portalReadService.attendance(orgId(), enrollNo);
            audit("PORTAL_READ_ATTENDANCE", enrollNo);
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Fee dues, read-only.
     *
     * <p>D5 — the arithmetic is finance's (0.2a); the portal shows it. There is no Pay button: that is 3.2
     * and gated on D-4. Showing a balance a guardian cannot yet pay is still an improvement on today, where
     * they cannot see it at all.
     */
    @RequestMapping(value = "/portal/dues", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse dues(final HttpServletRequest request) {
        try {
            String enrollNo = mineOrNull(request);
            if (enrollNo == null) return notYours();
            Map<String, Object> out = portalReadService.dues(orgId(), enrollNo);
            audit("PORTAL_READ_DUES", enrollNo);
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Homework set for this child's class, with what has been recorded for them. */
    @RequestMapping(value = "/portal/homework", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse homework(final HttpServletRequest request) {
        try {
            String enrollNo = mineOrNull(request);
            if (enrollNo == null) return notYours();

            // The child ENTITY, not just the number: homework is set per class, so the read needs gradeId.
            Student child = null;
            GuardianPortalAccess g = guardian();
            for (Student s : childResolver.myChildren(orgId(), g.getGuardianId())) {
                if (enrollNo.equals(s.getEnrollNo())) { child = s; break; }
            }
            if (child == null) return notYours();

            List<Map<String, Object>> out = portalReadService.homework(orgId(), child);
            audit("PORTAL_READ_HOMEWORK", enrollNo);
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Slice 3.5 — school notices addressed to guardians or to everyone.
     *
     * <p>The class used for a ONE_CLASS notice is taken from the guardian's children: a guardian with a
     * child in Class 5 sees Class 5's notices. With several children in different classes the FIRST match
     * wins per notice, which is why {@code reaches} is asked once per child rather than once per guardian —
     * a parent of two must not miss one child's class notice because the other child is in a different
     * class.
     */
    @RequestMapping(value = "/portal/notices", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse notices() {
        try {
            GuardianPortalAccess g = guardian();
            if (g == null) return notYours();

            // One call per distinct class the guardian has a child in, merged. Small by construction —
            // a guardian has a handful of children, not a roster.
            Set<Long> grades = new LinkedHashSet<>();
            for (Student s : childResolver.myChildren(orgId(), g.getGuardianId())) {
                grades.add(s.getGradeId());
            }
            if (grades.isEmpty()) grades.add(null);   // a guardian with no enrolled child still sees general notices

            List<Map<String, Object>> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Long gradeId : grades) {
                for (Map<String, Object> n : portalReadService.notices(orgId(),
                        PortalSubjectType.GUARDIAN, gradeId)) {
                    // De-duplicated across children: a whole-school notice must appear once, not once per
                    // child. Keyed on title+date because the portal map deliberately carries no id.
                    String key = n.get("publishedOn") + "|" + n.get("title");
                    if (seen.add(key)) out.add(n);
                }
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── Slice edu-3.4: meetings. THE FIRST WRITES ON THE PORTAL SURFACE ─────────────────────────────
    //
    // 3.1 D4 said "every mapping is GET", and that rule was load-bearing: a read-only surface cannot be
    // tricked into changing anything. These two endpoints are a deliberate, narrow exception, stated here
    // rather than slipped in.
    //
    // What keeps them safe is that they write EXACTLY ONE thing — a booking against a published slot — and
    // that both are scoped by the same authority as every read on this controller:
    //   · the evening must be OPEN and in the caller's org  (MeetingService)
    //   · the slot must belong to that evening               (the core, by ref + org)
    //   · the booking is made for THIS guardian              (guardian(), never a request parameter)
    //
    // A guardian cannot book for another family, because the attendee is taken from the resolved session
    // and never from the request. That is the same rule 3.1 D3 applied to the invitation email, and it is
    // why there is no `guardianId` parameter anywhere below.

    /** What is open to book, with each teacher's name and how many places are left. */
    @RequestMapping(value = "/portal/meetings", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public GenericResponse meetings() {
        try {
            GuardianPortalAccess g = guardian();
            if (g == null) return notYours();

            MeetingEvent event = meetingService.openEvent(orgId());
            if (event == null) {
                // No open evening is a normal state, not an error — a school runs one or two a year.
                return new GenericResponse("SUCCESS", "", List.of());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("eventId", event.getId());
            out.put("title", event.getTitle());
            out.put("eventDate", event.getEventDate() == null ? null : event.getEventDate().toString());
            out.put("notes", event.getNotes());
            out.put("slots", meetingService.slotsFor(event, orgId()));
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Book a slot. The attendee is THIS guardian — there is no parameter for it, by design.
     *
     * <p>Idempotent through the core: a double-clicked Book returns the booking they already hold rather
     * than an error, because they asked for a booking and have one.
     */
    @RequestMapping(value = "/portal/meetings/book", method = RequestMethod.POST)
    @ResponseBody
    // NOT @Transactional — same reason as MeetingController.publishMeetingSlots: this calls the
    // scheduling core over HTTP and writes nothing here. ChildResolver.resolveGuardian manages its own
    // transaction for the INVITED->ACTIVE flip, which is the only local write on this path.
    public GenericResponse bookMeeting(final HttpServletRequest request) {
        try {
            GuardianPortalAccess g = guardian();
            if (g == null) return notYours();

            Long slotId = parseLongOrNull(request.getParameter("slotId"));
            if (slotId == null) return new GenericResponse("ERROR", "A slot is required");

            MeetingEvent event = meetingService.openEvent(orgId());
            if (event == null) return new GenericResponse("FAILED", "There is no evening open for booking.");

            // guardianId comes from the RESOLVED SESSION, never the request — see the block comment above.
            Map<String, Object> res = meetingService.book(event, slotId, g.getGuardianId());
            auditService.record("PORTAL_MEETING_BOOKED", "MeetingEvent", String.valueOf(event.getId()),
                    "slotId=" + slotId + " guardianId=" + g.getGuardianId());
            return new GenericResponse("SUCCESS", "Booked", res);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // A closed evening, a taken slot, or the core being unreachable — all answers a family can act
            // on, rather than a 500. Logged too: a friendly refusal must not cost the diagnosis.
            appUtil.le(getClass(), e);
            return new GenericResponse("FAILED", e.getMessage());
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    // NOTE: there is deliberately NO behaviour-notes endpoint (D4). 2.5's log was written by staff with no
    // expectation that a guardian would read it the next day; exposing it retroactively would change the
    // contract its authors wrote under. It needs a per-note "shared with guardian" decision — a feature,
    // not a filter — and that is tracked in the programme's carried requirements.

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The gate. Resolves the guardian, then intersects the requested child with the derived set.
     *
     * <p>Every data endpoint above calls this FIRST and returns {@link #notYours()} on null. There is no
     * other way into a child's data from this controller.
     */
    private String mineOrNull(HttpServletRequest request) {
        GuardianPortalAccess g = guardian();
        if (g == null) return null;
        String requested = request.getParameter("enrollNo");
        if (!StringUtils.hasText(requested)) return null;
        return childResolver.requireMine(orgId(), g.getGuardianId(), requested);
    }

    /**
     * An external party reading a child's record is worth a permanent trail.
     *
     * <p>Best-effort: a failed audit must not deny a guardian their own child's results, and the outbox
     * retries. The read has already been authorised by this point.
     */
    private void audit(String action, String enrollNo) {
        try {
            auditService.record(action, "Student", enrollNo, "portal read by " + email());
        } catch (Exception e) {
            appUtil.le(getClass(), e);
        }
    }

    private String gradeLabel(Grade g) {
        String n = g.getName() == null ? "Class" : g.getName();
        return g.getSection() == null || g.getSection().isBlank() ? n : n + " " + g.getSection();
    }
}
