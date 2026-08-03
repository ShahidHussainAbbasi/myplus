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
import com.myplus.education.repository.*;
import com.myplus.education.service.BehaviourNoteRules;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.service.StudentVisibilityService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 2.5 — the behaviour / discipline log. The last slice of Phase 2.
 * Design: microservices/docs/slices/edu-2.5-discipline-log.md
 *
 * <p><b>There is deliberately no edit endpoint and no delete endpoint.</b> A note is an account of what
 * someone reported at the time; correcting it writes a NEW note and supersedes the original (D3). That is
 * not enforced by a privilege check — the operations simply do not exist, which is the only version of
 * "immutable" that cannot be argued around later.
 *
 * <p><b>WRITE tier</b> to record, like marks (1.3 D6) and homework (2.4): this is teacher work. Reads are
 * scoped by {@code StudentVisibilityService}, the same rule every student-facing screen uses — there is no
 * new confidentiality tier here, and §6 of the design says plainly that safeguarding records need one.
 */
@Controller
public class BehaviourController {

    @Autowired private BehaviourNoteRepository behaviourNoteRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private StudentVisibilityService studentVisibilityService;
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

    private static LocalDate parseDate(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return null; }
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────────

    /**
     * One student's history, or the school-wide recent view.
     *
     * <p>Superseded notes are RETURNED, flagged — never filtered out. Hiding a corrected note would
     * reproduce exactly what append-only exists to prevent; the screen strikes them through instead.
     */
    @RequestMapping(value = "/getBehaviourNotes", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getBehaviourNotes(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String enrollNo = request.getParameter("enrollNo");

            List<BehaviourNote> notes;
            if (StringUtils.hasText(enrollNo)) {
                if (!studentVisibilityService.isVisible(org, uid, enrollNo)) {
                    // NOT_FOUND rather than FORBIDDEN: telling an unauthorised caller that a student
                    // exists elsewhere is itself a disclosure (1.5's rule, applied to sensitive data).
                    return new GenericResponse("NOT_FOUND", "Student not found");
                }
                notes = behaviourNoteRepository.findByStudentScoped(enrollNo.trim(), org, uid);
            } else {
                // The school-wide view is filtered to the caller's visible students, so a branch-scoped
                // teacher never sees another campus's notes.
                Set<String> visible = new HashSet<>();
                for (Student s : studentVisibilityService.visibleStudents(org, uid)) {
                    if (s.getEnrollNo() != null) visible.add(s.getEnrollNo());
                }
                notes = new ArrayList<>();
                for (BehaviourNote n : behaviourNoteRepository.findScoped(org, uid)) {
                    if (visible.contains(n.getStudentEnrollNo())) notes.add(n);
                }
            }

            List<Map<String, Object>> out = new ArrayList<>();
            for (BehaviourNote n : notes) out.add(dto(n));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("notes", out);
            // Counts use ACTIVE only, so one incident recorded twice (a note and its correction) is
            // counted once — while the history above still shows both.
            List<BehaviourNote> active = BehaviourNoteRules.activeOnly(notes);
            long concerns = active.stream().filter(n -> n.getType() == BehaviourType.CONCERN).count();
            long positives = active.stream().filter(n -> n.getType() == BehaviourType.POSITIVE).count();
            payload.put("concerns", concerns);
            payload.put("positives", positives);
            return new GenericResponse("SUCCESS", "", payload);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── writes (WRITE tier — teacher work, like marks) ──────────────────────────────────────────

    /**
     * Record a note. INSERT only — this endpoint never updates an existing description (D3).
     *
     * <p>D4: the AUTHOR is supplied explicitly and is not defaulted to the session user, because an office
     * clerk typing up what a teacher reported is normal. The typist is recorded separately as {@code
     * userId}, and both matter when the note is disputed.
     */
    @RequestMapping(value = "/saveBehaviourNote", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @Transactional
    public GenericResponse saveBehaviourNote(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String enrollNo = request.getParameter("enrollNo");
            String description = request.getParameter("description");
            if (!StringUtils.hasText(enrollNo)) return new GenericResponse("ERROR", "Student is required");

            Student student = findVisible(org, uid, enrollNo);
            if (student == null) return new GenericResponse("NOT_FOUND", "Student not found");

            LocalDate occurredOn = parseDate(request.getParameter("occurredOn"));
            String problem = BehaviourNoteRules.validate(description, occurredOn, LocalDate.now());
            if (problem != null) return new GenericResponse("FAILED", problem);

            boolean parentInformed = "true".equalsIgnoreCase(request.getParameter("parentInformed"));
            LocalDate parentInformedOn = parseDate(request.getParameter("parentInformedOn"));
            String coherence = BehaviourNoteRules.validateParentInformed(parentInformed, parentInformedOn);
            if (coherence != null) return new GenericResponse("FAILED", coherence);

            BehaviourType type;
            try {
                String raw = request.getParameter("type");
                type = StringUtils.hasText(raw)
                        ? BehaviourType.valueOf(raw.trim().toUpperCase(Locale.ROOT))
                        : BehaviourType.NEUTRAL;
            } catch (Exception e) {
                return new GenericResponse("ERROR", "Unrecognised note type");
            }

            BehaviourNote note = build(org, uid, student, type, request, occurredOn,
                    description, parentInformed, parentInformedOn);
            note = behaviourNoteRepository.save(note);

            auditService.record("BEHAVIOUR_NOTE_ADDED", "BehaviourNote", String.valueOf(note.getId()),
                    "student=" + note.getStudentEnrollNo() + " type=" + type
                            + " author=" + note.getRecordedByStaffName());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", note.getId());
            return new GenericResponse("SUCCESS", "Note recorded", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Correct a note: write a NEW one and mark the original superseded, linked.
     *
     * <p>This is the ONLY way to change what the log says. The original keeps its author, its date and its
     * original wording — which is the whole point, because the record's value is that it says what someone
     * reported at the time.
     */
    @RequestMapping(value = "/supersedeBehaviourNote", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @Transactional
    public GenericResponse supersedeBehaviourNote(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long id = parseLong(request.getParameter("id"));
            String description = request.getParameter("description");
            if (id == null) return new GenericResponse("ERROR", "Note is required");

            BehaviourNote original = behaviourNoteRepository.findByIdScoped(id, org, uid).orElse(null);
            String problem = BehaviourNoteRules.canSupersede(original);
            if (problem != null) {
                return new GenericResponse(original == null ? "NOT_FOUND" : "FAILED", problem);
            }
            if (!studentVisibilityService.isVisible(org, uid, original.getStudentEnrollNo())) {
                return new GenericResponse("NOT_FOUND", "Note not found");
            }
            LocalDate occurredOn = parseDate(request.getParameter("occurredOn"));
            if (occurredOn == null) occurredOn = original.getOccurredOn();
            String invalid = BehaviourNoteRules.validate(description, occurredOn, LocalDate.now());
            if (invalid != null) return new GenericResponse("FAILED", invalid);

            Student student = findVisible(org, uid, original.getStudentEnrollNo());
            BehaviourType type;
            try {
                String raw = request.getParameter("type");
                type = StringUtils.hasText(raw)
                        ? BehaviourType.valueOf(raw.trim().toUpperCase(Locale.ROOT))
                        : original.getType();
            } catch (Exception e) {
                return new GenericResponse("ERROR", "Unrecognised note type");
            }

            BehaviourNote replacement = build(org, uid, student, type, request, occurredOn, description,
                    original.isParentInformed(), original.getParentInformedOn());
            // Carry the original author forward unless a new one is supplied: the correction is usually
            // typed by someone else, but it is still THAT teacher's account being restated.
            if (replacement.getRecordedByStaffId() == null) {
                replacement.setRecordedByStaffId(original.getRecordedByStaffId());
                replacement.setRecordedByStaffName(original.getRecordedByStaffName());
            }
            replacement = behaviourNoteRepository.save(replacement);

            original.setStatus(NoteStatus.SUPERSEDED);
            original.setSupersededByNoteId(replacement.getId());
            original.setUpdated(LocalDateTime.now());
            behaviourNoteRepository.save(original);

            auditService.record("BEHAVIOUR_NOTE_SUPERSEDED", "BehaviourNote", String.valueOf(original.getId()),
                    "student=" + original.getStudentEnrollNo() + " replacedBy=" + replacement.getId());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", replacement.getId());
            out.put("supersededId", original.getId());
            return new GenericResponse("SUCCESS", "Note corrected — the original is kept", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Shared construction so a note and its correction cannot drift apart in shape. */
    private BehaviourNote build(Long org, Long uid, Student student, BehaviourType type,
                                HttpServletRequest request, LocalDate occurredOn, String description,
                                boolean parentInformed, LocalDate parentInformedOn) {
        Long authorId = parseLong(request.getParameter("recordedByStaffId"));
        String authorName = null;
        if (authorId != null) {
            Staff author = staffRepository.findByIdScoped(authorId, org, uid).orElse(null);
            // An unresolvable author is dropped rather than guessed — a wrong name on a contested record
            // is worse than none.
            if (author == null) authorId = null; else authorName = author.getName();
        }
        return BehaviourNote.builder()
                .studentEnrollNo(student.getEnrollNo())
                .studentName(student.getName())
                .type(type)
                .category(StringUtils.hasText(request.getParameter("category"))
                        ? request.getParameter("category").trim() : null)
                .occurredOn(occurredOn != null ? occurredOn : LocalDate.now())
                .description(description.trim())
                .action(StringUtils.hasText(request.getParameter("action"))
                        ? request.getParameter("action").trim() : null)
                .recordedByStaffId(authorId)
                .recordedByStaffName(authorName)
                .parentInformed(parentInformed)
                .parentInformedOn(parentInformedOn)
                .status(NoteStatus.ACTIVE)
                .userId(uid).organizationId(org)
                .dated(LocalDateTime.now()).updated(LocalDateTime.now())
                .build();
    }

    private Student findVisible(Long org, Long uid, String enrollNo) {
        String wanted = enrollNo.trim();
        for (Student s : studentVisibilityService.visibleStudents(org, uid)) {
            if (wanted.equals(s.getEnrollNo())) return s;
        }
        return null;
    }

    private Map<String, Object> dto(BehaviourNote n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("enrollNo", n.getStudentEnrollNo());
        m.put("studentName", n.getStudentName());
        m.put("type", n.getType() == null ? null : n.getType().name());
        m.put("category", n.getCategory());
        m.put("occurredOn", n.getOccurredOn() == null ? null : n.getOccurredOn().toString());
        m.put("description", n.getDescription());
        m.put("action", n.getAction());
        // Both, always: the account is only defensible if it says who reported it AND who typed it (D4).
        m.put("recordedByStaffId", n.getRecordedByStaffId());
        m.put("recordedByStaffName", n.getRecordedByStaffName());
        m.put("parentInformed", n.isParentInformed());
        m.put("parentInformedOn", n.getParentInformedOn() == null ? null
                : n.getParentInformedOn().toString());
        m.put("status", n.getStatus() == null ? null : n.getStatus().name());
        m.put("supersededByNoteId", n.getSupersededByNoteId());
        m.put("dated", n.getDated() == null ? null : n.getDated().toString());
        return m;
    }
}
