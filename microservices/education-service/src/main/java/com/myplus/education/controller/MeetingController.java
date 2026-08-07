package com.myplus.education.controller;

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

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.MeetingEvent;
import com.myplus.education.entity.MeetingEventStatus;
import com.myplus.education.repository.MeetingEventRepository;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.service.MeetingService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice edu-3.4 — the SCHOOL side of guardian–teacher meetings.
 *
 * <p>Separate from the portal surface, exactly as 3.1 and 3.5 separated theirs: this is where an evening is
 * created, opened, closed and filled with slots, so a family's session can never reach it however roles
 * evolve.
 *
 * <p><b>ADMIN on open/close and on publishing slots</b> — committing every teacher's evening, and deciding
 * when families may book it, is a policy act in the same tier as fee settings and report-card publication
 * (D-3's map). Creating a draft evening is ordinary staff work.
 */
@Controller
public class MeetingController {

    @Autowired private MeetingEventRepository eventRepository;
    @Autowired private MeetingService meetingService;
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

    /** The school's evenings, newest first. */
    @RequestMapping(value = "/getMeetingEvents", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getMeetingEvents() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (MeetingEvent e : eventRepository.findScoped(orgId(), userId())) out.add(toMap(e));
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Create or rename an evening. */
    @RequestMapping(value = "/saveMeetingEvent", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @Transactional
    public GenericResponse saveMeetingEvent(final HttpServletRequest request) {
        try {
            String title = request.getParameter("title");
            if (!StringUtils.hasText(title)) return new GenericResponse("FAILED", "A title is required");

            Long id = parseLong(request.getParameter("id"));
            MeetingEvent e;
            if (id != null) {
                // Anti-IDOR: an edit names a row by a client-supplied id, so resolve it in the tenant.
                e = eventRepository.findByIdScoped(id, orgId(), userId()).orElse(null);
                if (e == null) return new GenericResponse("NOT_FOUND", "Evening not found");
            } else {
                e = new MeetingEvent();
                e.setStatus(MeetingEventStatus.OPEN);
                e.setDated(LocalDateTime.now());
            }
            e.setTitle(title.trim());
            e.setNotes(request.getParameter("notes"));
            if (StringUtils.hasText(request.getParameter("eventDateStr"))) {
                e.setEventDate(appUtil.getLocalDate(request.getParameter("eventDateStr")));
            }
            e.setUserId(userId());
            e.setOrganizationId(orgId());
            e.setUpdated(LocalDateTime.now());
            eventRepository.save(e);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", e.getId());
            return new GenericResponse("SUCCESS", "Evening saved", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Publish one teacher's slots by cutting a window into equal pieces.
     *
     * <p>Idempotent through the core, so re-running for a teacher whose slots exist reports them rather
     * than failing — a school extending an evening from 18:00–19:00 to 18:00–20:00 gets the extra hour.
     */
    /*
     * ⚠ NOT @Transactional, and that is a correction rather than an omission.
     *
     * The first cut wrapped this in a transaction. It writes nothing to education's database — it reads an
     * evening and calls the scheduling core over HTTP — so the transaction bought nothing and cost two
     * things:
     *
     *   1. It held a DB connection open across a remote round trip, which the platform's own performance
     *      standard warns against ("keep inter-service calls off hot paths").
     *   2. Far worse, when the inner call threw, Spring marked the transaction ROLLBACK-ONLY. The catch
     *      below then produced a friendly message, the outer commit failed anyway, and the caller got
     *      "Transaction silently rolled back because it has been marked as rollback-only" — a message that
     *      says nothing about what actually went wrong. **The transaction turned a diagnosable error into
     *      an undiagnosable one.**
     *
     * Same shape as SCHED-1 B2's retry: a catch inside a transaction cannot recover what the transaction
     * has already decided.
     */
    @RequestMapping(value = "/publishMeetingSlots", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public GenericResponse publishMeetingSlots(final HttpServletRequest request) {
        try {
            Long eventId = parseLong(request.getParameter("eventId"));
            Long staffId = parseLong(request.getParameter("staffId"));
            String from = request.getParameter("from");
            String to = request.getParameter("to");
            if (eventId == null || staffId == null) {
                return new GenericResponse("ERROR", "An evening and a teacher are required");
            }
            if (!StringUtils.hasText(from) || !StringUtils.hasText(to)) {
                return new GenericResponse("ERROR", "A start and an end time are required");
            }
            MeetingEvent e = eventRepository.findByIdScoped(eventId, orgId(), userId()).orElse(null);
            if (e == null) return new GenericResponse("NOT_FOUND", "Evening not found");

            Integer minutes = null;
            String m = request.getParameter("minutes");
            if (StringUtils.hasText(m)) minutes = Integer.valueOf(m.trim());

            Map<String, Object> res = meetingService.publishSlots(e, staffId, from, to, minutes);
            Object data = res == null ? null : res.get("data");
            Map<String, Object> out = data instanceof Map<?, ?> dm
                    ? new LinkedHashMap<>(cast(dm)) : new LinkedHashMap<>();

            auditService.record("MEETING_SLOTS_PUBLISHED", "MeetingEvent", String.valueOf(eventId),
                    "staffId=" + staffId + " " + from + "→" + to + " " + out);
            return new GenericResponse("SUCCESS", "Slots published — " + out, out);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // A refusal the school can act on (bad window, scheduling core down) rather than a 500.
            // LOGGED as well as returned: the first cut swallowed these silently, so when the publish
            // failed there was nothing in the log to say why — the message reached the screen and the
            // cause reached nobody. A friendly refusal is not a reason to lose the diagnosis.
            appUtil.le(getClass(), e);
            return new GenericResponse("FAILED", e.getMessage());
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Open or close booking.
     *
     * <p><b>Closing does not cancel anything.</b> Existing bookings stand — a school closing an evening is
     * finishing the booking window, not calling the evening off, and silently dropping families' slots
     * would be the worst possible reading of that button.
     */
    @RequestMapping(value = "/setMeetingEventStatus", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse setMeetingEventStatus(final HttpServletRequest request) {
        try {
            Long id = parseLong(request.getParameter("id"));
            String status = request.getParameter("status");
            if (id == null || !StringUtils.hasText(status)) {
                return new GenericResponse("ERROR", "An evening and a status are required");
            }
            MeetingEvent e = eventRepository.findByIdScoped(id, orgId(), userId()).orElse(null);
            if (e == null) return new GenericResponse("NOT_FOUND", "Evening not found");

            MeetingEventStatus next;
            try {
                next = MeetingEventStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return new GenericResponse("FAILED", "Unknown status: " + status);
            }
            e.setStatus(next);
            e.setUpdated(LocalDateTime.now());
            eventRepository.save(e);
            auditService.record("MEETING_EVENT_" + next.name(), "MeetingEvent", String.valueOf(id),
                    "title=" + e.getTitle());
            return new GenericResponse("SUCCESS",
                    next == MeetingEventStatus.OPEN ? "Booking is open" : "Booking is closed");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** The slots published for an evening — the staff view of what families can see. */
    @RequestMapping(value = "/getMeetingSlots", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getMeetingSlots(final HttpServletRequest request) {
        try {
            Long eventId = parseLong(request.getParameter("eventId"));
            if (eventId == null) return new GenericResponse("ERROR", "An evening is required");
            MeetingEvent e = eventRepository.findByIdScoped(eventId, orgId(), userId()).orElse(null);
            if (e == null) return new GenericResponse("NOT_FOUND", "Evening not found");
            return new GenericResponse("SUCCESS", "", meetingService.slotsFor(e, orgId()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    private Map<String, Object> toMap(MeetingEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("title", e.getTitle());
        m.put("eventDate", e.getEventDate() == null ? null : e.getEventDate().toString());
        m.put("status", e.getStatus() == null ? null : e.getStatus().name());
        m.put("notes", e.getNotes());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
