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

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.*;
import com.myplus.education.repository.NoticeRepository;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.service.EduNotifyService;
import com.myplus.education.service.NoticeAudienceResolver;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 3.5 — the SCHOOL side of notices: writing them, and publishing them.
 * Design: microservices/docs/slices/edu-3.5-notices.md
 *
 * <p>Separate from the portal reads on purpose, exactly as 3.1 separated {@code PortalAccessController}
 * from {@code GuardianPortalController}: this one is staff-facing and is the only place a notice is
 * created or published, so a portal session can never reach an endpoint that publishes, however roles
 * evolve.
 *
 * <p><b>Publishing is {@code ADMIN_PRIVILEGE}</b> — addressing the whole school community is a policy act,
 * the same tier as fee settings and report-card publication (D-3's map). Writing a draft is ordinary staff
 * work.
 */
@Controller
public class NoticeController {

    /**
     * Governs DELIVERY, not visibility (D6/C2). Turning it off stops the emails; the notice is still
     * published and still readable in both portals — which is the whole point of finding C, and what the
     * gate asserts.
     */
    public static final String NOTICES_ENABLED = "edu.notify.notices";

    @Autowired private NoticeRepository noticeRepository;
    @Autowired private NoticeAudienceResolver audienceResolver;
    @Autowired private EduNotifyService notifyService;
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

    /** The school's own list — drafts included. */
    @RequestMapping(value = "/getNotices", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getNotices() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Notice n : noticeRepository.findScoped(orgId(), userId())) out.add(toMap(n));
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * How many people a notice WOULD reach, before anyone commits to it.
     *
     * <p>Exists because "you are about to tell 412 families something" is the fact the person clicking
     * needs and cannot otherwise see. The same reasoning as 3.1's {@code childCount} on the invite: the
     * consequence of the act is surfaced at the moment of the act.
     *
     * <p>Counts ADDRESSES, and the screen must say so — a whole-school notice reaches every family in the
     * portal regardless of whether an address exists for them.
     */
    @RequestMapping(value = "/getNoticeReach", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getNoticeReach(final HttpServletRequest request) {
        try {
            Notice probe = new Notice();
            probe.setAudience(parseAudience(request.getParameter("audience")));
            probe.setGradeId(parseLong(request.getParameter("gradeId")));
            probe.setStatus(NoticeStatus.PUBLISHED);   // a reach question is about the published state
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("recipients", audienceResolver.recipients(orgId(), probe).size());
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Create or edit a DRAFT. Editing a published notice is refused — see below. */
    @RequestMapping(value = "/saveNotice", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @Transactional
    public GenericResponse saveNotice(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String title = request.getParameter("title");
            String body = request.getParameter("body");
            if (!StringUtils.hasText(title)) return new GenericResponse("FAILED", "A title is required");
            if (!StringUtils.hasText(body)) return new GenericResponse("FAILED", "A message is required");

            Long id = parseLong(request.getParameter("id"));
            Notice n;
            if (id != null) {
                // Anti-IDOR: an edit names a row by a client-supplied id, so resolve it WITHIN the tenant.
                n = noticeRepository.findByIdScoped(id, org, uid).orElse(null);
                if (n == null) return new GenericResponse("NOT_FOUND", "Notice not found");
                // A PUBLISHED notice is not editable. Families have already been told; silently rewriting
                // what they were told is the defect 1.5 D5 refused for report cards and 2.5 D3 refused for
                // behaviour notes. Write a new notice instead.
                if (n.getStatus() == NoticeStatus.PUBLISHED) {
                    return new GenericResponse("FAILED",
                            "This notice has already been published and cannot be edited. "
                                    + "Publish a new notice instead.");
                }
            } else {
                n = new Notice();
                n.setStatus(NoticeStatus.DRAFT);
                n.setDated(LocalDateTime.now());
            }

            NoticeAudience audience = parseAudience(request.getParameter("audience"));
            Long gradeId = parseLong(request.getParameter("gradeId"));
            // A class notice with no class would reach nobody on send and everybody on a careless read.
            // Refused at entry rather than resolved either way — the same "fail loudly at the boundary"
            // choice slice B made for money.
            if (audience == NoticeAudience.ONE_CLASS && gradeId == null) {
                return new GenericResponse("FAILED", "Choose a class for a class notice");
            }

            n.setTitle(title.trim());
            n.setBody(body);
            n.setAudience(audience);
            n.setGradeId(audience == NoticeAudience.ONE_CLASS ? gradeId : null);
            if (StringUtils.hasText(request.getParameter("pinnedUntilStr"))) {
                n.setPinnedUntil(appUtil.getLocalDate(request.getParameter("pinnedUntilStr")));
            }
            n.setUserId(uid);
            n.setOrganizationId(org);
            n.setUpdated(LocalDateTime.now());
            noticeRepository.save(n);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", n.getId());
            return new GenericResponse("SUCCESS", "Notice saved", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Publish: make it readable, and queue the delivery. <b>One transaction.</b>
     *
     * <p>The record and the delivery commit together (D3). Nothing waits on SMTP — the outbox relay owns
     * the send, so a school with 400 families is not holding an HTTP request open for 400 hops.
     *
     * <p>The response says <b>queued</b>, never sent. Reporting "sent" for work handed to a relay is the
     * defect this slice is fixing one layer up (finding A), and it would be no better here.
     */
    @RequestMapping(value = "/publishNotice", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse publishNotice(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long id = parseLong(request.getParameter("id"));
            if (id == null) return new GenericResponse("ERROR", "Notice is required");

            Notice n = noticeRepository.findByIdScoped(id, org, uid).orElse(null);
            if (n == null) return new GenericResponse("NOT_FOUND", "Notice not found");
            if (n.getStatus() == NoticeStatus.PUBLISHED) {
                // Idempotent: a double-clicked Publish must not send everything twice.
                return new GenericResponse("SUCCESS", "This notice is already published");
            }

            n.setStatus(NoticeStatus.PUBLISHED);
            n.setPublishedOn(LocalDate.now());
            n.setUpdated(LocalDateTime.now());
            noticeRepository.save(n);

            // Resolved NOW, from live enrolment — never stored (D2).
            Set<String> recipients = audienceResolver.recipients(org, n);
            int queued = notifyService.queueAll("NOTICE", NOTICES_ENABLED, n.getTitle(), n.getBody(), recipients);

            auditService.record("NOTICE_PUBLISHED", "Notice", String.valueOf(n.getId()),
                    "audience=" + n.getAudience() + " recipients=" + recipients.size() + " queued=" + queued);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", n.getId());
            out.put("recipients", recipients.size());
            out.put("queued", queued);
            // Both numbers, because they legitimately differ: a family with no address on record still
            // reads the notice in the portal. Saying only "queued 380" would look like a failure.
            return new GenericResponse("SUCCESS",
                    "Published — visible to families now, and queued for " + queued
                            + " of " + recipients.size() + " address(es)", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Delete a notice. ADMIN, because removing what a school told its families is a policy act too. */
    @RequestMapping(value = "/deleteNotice", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse deleteNotice(final HttpServletRequest request) {
        try {
            Long id = parseLong(request.getParameter("id"));
            if (id == null) return new GenericResponse("ERROR", "Notice is required");
            Notice n = noticeRepository.findByIdScoped(id, orgId(), userId()).orElse(null);
            if (n == null) return new GenericResponse("NOT_FOUND", "Notice not found");
            noticeRepository.delete(n);
            auditService.record("NOTICE_DELETED", "Notice", String.valueOf(id), "title=" + n.getTitle());
            return new GenericResponse("SUCCESS", "Notice deleted");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Unknown or absent → WHOLE_SCHOOL, which is the screen's default and the commonest case. */
    private static NoticeAudience parseAudience(String s) {
        if (!StringUtils.hasText(s)) return NoticeAudience.WHOLE_SCHOOL;
        try {
            return NoticeAudience.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NoticeAudience.WHOLE_SCHOOL;
        }
    }

    private Map<String, Object> toMap(Notice n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("title", n.getTitle());
        m.put("body", n.getBody());
        m.put("audience", n.getAudience() == null ? null : n.getAudience().name());
        m.put("gradeId", n.getGradeId());
        m.put("status", n.getStatus() == null ? null : n.getStatus().name());
        m.put("publishedOn", n.getPublishedOn() == null ? null : n.getPublishedOn().toString());
        m.put("pinnedUntil", n.getPinnedUntil() == null ? null : n.getPinnedUntil().toString());
        return m;
    }
}
