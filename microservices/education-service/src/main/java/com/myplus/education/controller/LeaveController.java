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
import com.myplus.common.settings.SettingsService;
import com.myplus.education.entity.*;
import com.myplus.education.repository.*;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.service.LeaveBalanceCalculator;
import com.myplus.education.service.LeaveBalanceCalculator.Balance;
import com.myplus.education.service.LeaveBalanceCalculator.TermRange;
import com.myplus.education.service.StaffAbsenceService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 2.3 — leave types, requests and derived balances.
 * Design: microservices/docs/slices/edu-2.3-staff-attendance-leave.md
 *
 * <p><b>Privilege split (D-3's three tiers, applied):</b> requesting leave is {@code WRITE_PRIVILEGE} —
 * a teacher asks for their own — while <b>deciding</b> it and managing types are {@code ADMIN_PRIVILEGE}.
 * That line matters: an approval writes absences that pull other teachers into cover.
 *
 * <p><b>D3, the convergence:</b> approving a request expands its range into one {@code StaffAbsence} per
 * in-session day, each carrying {@code leaveId}, through the shared {@link StaffAbsenceService}. Cancelling
 * or rejecting an approved request clears them through the same owner, which cancels 2.2's substitutions.
 */
@Controller
public class LeaveController {

    /** D2 — small schools mark leave directly; large ones route it through a head. */
    public static final String REQUIRE_APPROVAL = "edu.leave.requireApproval";

    @Autowired private LeaveTypeRepository leaveTypeRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private StaffAbsenceRepository staffAbsenceRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TermRepository termRepository;
    @Autowired private StaffAbsenceService staffAbsenceService;
    @Autowired private EduAuditService auditService;
    @Autowired private SettingsService settingsService;
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

    private static Integer parseInt(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return Integer.valueOf(s.trim()); } catch (Exception e) { return null; }
    }

    private static LocalDate parseDate(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return null; }
    }

    private boolean requireApproval() {
        // Fails to REQUIRING approval: granting leave nobody approved is the worse mistake.
        try { return settingsService.getBool(REQUIRE_APPROVAL); } catch (Exception e) { return true; }
    }

    // ── leave types (ADMIN) ─────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/getLeaveTypes", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getLeaveTypes() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (LeaveType t : leaveTypeRepository.findScoped(orgId(), userId())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.getId());
                m.put("name", t.getName());
                m.put("annualQuota", t.getAnnualQuota());
                m.put("paid", t.isPaid());
                m.put("sequence", t.getSequence());
                out.add(m);
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/saveLeaveType", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse saveLeaveType(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String name = request.getParameter("name");
            if (!StringUtils.hasText(name)) return new GenericResponse("ERROR", "Leave type name is required");

            String idStr = request.getParameter("id");
            LeaveType type;
            if (StringUtils.hasText(idStr)) {
                // Anti-IDOR: an unscoped findById would let a caller re-quota another school's leave.
                type = leaveTypeRepository.findByIdScoped(Long.valueOf(idStr.trim()), org, uid).orElse(null);
                if (type == null) return new GenericResponse("NOT_FOUND", "Leave type not found");
            } else {
                // Indexed EXISTS, not a full-table scan (finding D).
                if (leaveTypeRepository.existsByNameScoped(name.trim(), org, uid)) {
                    return new GenericResponse("FOUND", "A '" + name.trim() + "' leave type already exists");
                }
                type = LeaveType.builder().userId(uid).organizationId(org).dated(LocalDateTime.now()).build();
            }
            Integer quota = parseInt(request.getParameter("annualQuota"));
            if (quota != null && quota < 0) {
                return new GenericResponse("FAILED", "An annual quota cannot be negative");
            }
            type.setName(name.trim());
            type.setAnnualQuota(quota);
            // Absent parameter = paid; only an explicit "false" makes it unpaid.
            type.setPaid(!"false".equalsIgnoreCase(request.getParameter("paid")));
            type.setSequence(parseInt(request.getParameter("sequence")));
            type.setUpdated(LocalDateTime.now());
            leaveTypeRepository.save(type);
            return new GenericResponse("SUCCESS", "Leave type saved");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Refused while requests reference it — deleting would orphan decisions people acted on. */
    @RequestMapping(value = "/deleteLeaveType", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @Transactional
    public GenericResponse deleteLeaveType(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long id = parseLong(request.getParameter("id"));
            if (id == null) return new GenericResponse("ERROR", "Leave type is required");
            LeaveType type = leaveTypeRepository.findByIdScoped(id, org, uid).orElse(null);
            if (type == null) return new GenericResponse("NOT_FOUND", "Leave type not found");

            LocalDate now = LocalDate.now();
            for (LeaveRequest r : leaveRequestRepository.findByYearScoped(
                    now.withDayOfYear(1).minusYears(5), now.plusYears(5), org, uid)) {
                if (Objects.equals(r.getLeaveTypeId(), id)) {
                    return new GenericResponse("FAILED",
                            "This leave type has requests against it. It cannot be deleted.");
                }
            }
            leaveTypeRepository.delete(type);
            return new GenericResponse("SUCCESS", "Leave type deleted");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── balances (derived) ──────────────────────────────────────────────────────────────────────

    /**
     * Balances for one staff member, or the whole school.
     *
     * <p>D1 — nothing is stored. {@code quota − approved days taken} is computed from the year's requests,
     * read in ONE query and handed to the pure {@link LeaveBalanceCalculator}. A stored balance is a cache
     * of a sum that goes wrong the moment a request is cancelled or back-dated, with nothing saying so.
     */
    @RequestMapping(value = "/getLeaveBalances", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getLeaveBalances(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long staffId = parseLong(request.getParameter("staffId"));
            int year = Optional.ofNullable(parseInt(request.getParameter("year")))
                    .orElse(LocalDate.now().getYear());
            LocalDate from = LocalDate.of(year, 1, 1), to = LocalDate.of(year, 12, 31);

            List<LeaveType> types = leaveTypeRepository.findScoped(org, uid);
            List<LeaveRequest> requests = staffId != null
                    ? leaveRequestRepository.findByStaffYearScoped(staffId, from, to, org, uid)
                    : leaveRequestRepository.findByYearScoped(from, to, org, uid);

            List<Map<String, Object>> out = new ArrayList<>();
            for (LeaveType t : types) {
                Balance b = LeaveBalanceCalculator.balanceFor(
                        t.getId(), t.getName(), t.getAnnualQuota(), requests, year);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("leaveTypeId", b.leaveTypeId());
                m.put("leaveTypeName", b.leaveTypeName());
                m.put("quota", b.quota());
                m.put("taken", b.taken());
                // Null remaining means UNCAPPED, not "none left" — the screen must not render it as 0.
                m.put("remaining", b.remaining());
                m.put("paid", t.isPaid());
                out.add(m);
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── requests ────────────────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/getLeaveRequests", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getLeaveRequests(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long staffId = parseLong(request.getParameter("staffId"));
            int year = Optional.ofNullable(parseInt(request.getParameter("year")))
                    .orElse(LocalDate.now().getYear());
            LocalDate from = LocalDate.of(year, 1, 1), to = LocalDate.of(year, 12, 31);

            List<LeaveRequest> requests = staffId != null
                    ? leaveRequestRepository.findByStaffYearScoped(staffId, from, to, org, uid)
                    : leaveRequestRepository.findByYearScoped(from, to, org, uid);

            List<Map<String, Object>> out = new ArrayList<>();
            for (LeaveRequest r : requests) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.getId());
                m.put("staffId", r.getStaffId());
                m.put("staffName", r.getStaffName());
                m.put("leaveTypeId", r.getLeaveTypeId());
                // The STORED name: renaming a type must not retitle a decision already taken.
                m.put("leaveTypeName", r.getLeaveTypeName());
                m.put("fromDate", r.getFromDate() == null ? null : r.getFromDate().toString());
                m.put("toDate", r.getToDate() == null ? null : r.getToDate().toString());
                m.put("daysCounted", r.getDaysCounted());
                m.put("status", r.getStatus() == null ? null : r.getStatus().name());
                m.put("reason", r.getReason());
                out.add(m);
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Submit a request. WRITE tier — a teacher asks for their own.
     *
     * <p>D5 — a request exceeding the balance is <b>recorded, with the overage named</b>. A teacher with two
     * days left asking for five is a conversation, not an error; refusing it would only push the
     * conversation off-system.
     */
    @RequestMapping(value = "/saveLeaveRequest", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @Transactional
    public GenericResponse saveLeaveRequest(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long staffId = parseLong(request.getParameter("staffId"));
            Long typeId = parseLong(request.getParameter("leaveTypeId"));
            LocalDate from = parseDate(request.getParameter("fromDate"));
            LocalDate to = parseDate(request.getParameter("toDate"));

            if (staffId == null) return new GenericResponse("ERROR", "Teacher is required");
            if (typeId == null) return new GenericResponse("ERROR", "Leave type is required");
            if (from == null || to == null) return new GenericResponse("ERROR", "Both dates are required");
            if (to.isBefore(from)) {
                return new GenericResponse("FAILED", "The end date is before the start date");
            }

            Staff staff = staffRepository.findByIdScoped(staffId, org, uid).orElse(null);
            if (staff == null) return new GenericResponse("NOT_FOUND", "Teacher not found");
            LeaveType type = leaveTypeRepository.findByIdScoped(typeId, org, uid).orElse(null);
            if (type == null) return new GenericResponse("NOT_FOUND", "Leave type not found");

            int days = LeaveBalanceCalculator.workingDaysIn(from, to, termRanges(org, uid));
            if (days == 0) {
                return new GenericResponse("FAILED",
                        "None of those dates fall inside a term, so no leave would be taken.");
            }

            // Read the prior balance BEFORE inserting. Reading it afterwards and subtracting this
            // request's days only works when the request was auto-approved: with approval required (the
            // default) the new row is PENDING, daysTaken counts APPROVED only, so subtracting removed it a
            // second time and the overage always came out 0 — the warning D5 exists for never fired.
            int alreadyTaken = LeaveBalanceCalculator.daysTaken(
                    leaveRequestRepository.findByStaffYearScoped(staffId,
                            LocalDate.of(from.getYear(), 1, 1), LocalDate.of(from.getYear(), 12, 31), org, uid),
                    typeId, from.getYear());

            LeaveRequest req = LeaveRequest.builder()
                    .staffId(staffId).staffName(staff.getName())
                    .leaveTypeId(typeId).leaveTypeName(type.getName())
                    .fromDate(from).toDate(to).daysCounted(days)
                    .reason(StringUtils.hasText(request.getParameter("reason"))
                            ? request.getParameter("reason").trim() : null)
                    .status(requireApproval() ? LeaveRequestStatus.PENDING : LeaveRequestStatus.APPROVED)
                    .userId(uid).organizationId(org)
                    .dated(LocalDateTime.now()).updated(LocalDateTime.now())
                    .build();
            if (!requireApproval()) {
                req.setDecidedByUserId(uid);
                req.setDecidedOn(LocalDateTime.now());
            }
            req = leaveRequestRepository.save(req);

            int opened = 0;
            if (req.getStatus() == LeaveRequestStatus.APPROVED) {
                opened = expandToAbsences(org, uid, req, staff.getName(), type.getName());
            }

            // The overage is NAMED, not hidden — that is the whole point of warning instead of blocking.
            // Uses the PRE-SAVE figure read above, so it is correct whether or not approval is required.
            int overage = LeaveBalanceCalculator.overageFor(type.getAnnualQuota(), alreadyTaken, days);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", req.getId());
            out.put("daysCounted", days);
            out.put("overage", overage);
            out.put("lessonsNeedingCover", opened);

            String msg = days + " day(s) requested"
                    + (overage > 0 ? " — this exceeds the " + type.getName() + " balance by " + overage
                        + " day(s)" : "")
                    + (opened > 0 ? "; " + opened + " lesson(s) now need cover" : "");
            return new GenericResponse("SUCCESS", msg, out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Approve, reject or cancel. ADMIN — an approval writes absences that pull other teachers into cover.
     *
     * <p>D6 — a REJECTED request is kept and audited, never deleted: "I asked and was refused" is exactly
     * what gets disputed later.
     */
    @RequestMapping(value = "/decideLeaveRequest", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse decideLeaveRequest(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long id = parseLong(request.getParameter("id"));
            String decision = request.getParameter("decision");
            if (id == null) return new GenericResponse("ERROR", "Request is required");
            if (!StringUtils.hasText(decision)) return new GenericResponse("ERROR", "Decision is required");

            LeaveRequest req = leaveRequestRepository.findByIdScoped(id, org, uid).orElse(null);
            if (req == null) return new GenericResponse("NOT_FOUND", "Leave request not found");

            LeaveRequestStatus target;
            try {
                target = LeaveRequestStatus.valueOf(decision.trim().toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return new GenericResponse("ERROR", "Unrecognised decision");
            }
            if (target == LeaveRequestStatus.PENDING) {
                return new GenericResponse("FAILED", "A request cannot be moved back to pending");
            }

            LeaveRequestStatus previous = req.getStatus();
            req.setStatus(target);
            req.setDecidedByUserId(uid);
            req.setDecidedOn(LocalDateTime.now());
            req.setUpdated(LocalDateTime.now());
            leaveRequestRepository.save(req);

            int opened = 0, cleared = 0;
            if (target == LeaveRequestStatus.APPROVED && previous != LeaveRequestStatus.APPROVED) {
                opened = expandToAbsences(org, uid, req, req.getStaffName(), req.getLeaveTypeName());
            } else if (previous == LeaveRequestStatus.APPROVED) {
                // Rejected or cancelled AFTER approval: the absences it created must go, and with them
                // 2.2's substitutions — through the one owner, never a second copy of the cascade.
                cleared = withdrawAbsences(org, uid, req);
            }

            auditService.record("LEAVE_" + target.name(), "LeaveRequest", String.valueOf(req.getId()),
                    "staff=" + req.getStaffName() + " type=" + req.getLeaveTypeName()
                            + " " + req.getFromDate() + ".." + req.getToDate()
                            + " days=" + req.getDaysCounted());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", target.name());
            out.put("lessonsNeedingCover", opened);
            out.put("absencesCleared", cleared);
            String msg = "Request " + target.name().toLowerCase(Locale.ROOT)
                    + (opened > 0 ? " — " + opened + " lesson(s) need cover" : "")
                    + (cleared > 0 ? " — " + cleared + " absence(s) withdrawn" : "");
            return new GenericResponse("SUCCESS", msg, out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** D4 — one StaffAbsence per in-session day, each carrying the leave id that authorises it. */
    private int expandToAbsences(Long org, Long uid, LeaveRequest req, String staffName, String typeName) {
        int opened = 0;
        for (LocalDate d : LeaveBalanceCalculator.sessionDaysIn(
                req.getFromDate(), req.getToDate(), termRanges(org, uid))) {
            opened += staffAbsenceService.openAbsence(org, uid, req.getStaffId(), staffName, d,
                    typeName, req.getId());
        }
        return opened;
    }

    /** Remove the absences an approval created — via the shared owner, so substitutions cascade. */
    private int withdrawAbsences(Long org, Long uid, LeaveRequest req) {
        int cleared = 0;
        for (LocalDate d : LeaveBalanceCalculator.sessionDaysIn(
                req.getFromDate(), req.getToDate(), termRanges(org, uid))) {
            StaffAbsence a = staffAbsenceRepository.findOneScoped(req.getStaffId(), d, org, uid).orElse(null);
            // Only withdraw what THIS request created: a register-marked absence on the same day is a
            // separate fact and must survive the leave being cancelled.
            if (a == null || !Objects.equals(a.getLeaveId(), req.getId())) continue;
            staffAbsenceService.clearAbsence(org, uid, a);
            cleared++;
        }
        return cleared;
    }

    /** The term spans, read once — the calculator stays pure by taking them as an argument. */
    private List<TermRange> termRanges(Long org, Long uid) {
        List<TermRange> out = new ArrayList<>();
        for (Term t : termRepository.findScoped(org, uid)) {
            if (t.getStartDate() != null && t.getEndDate() != null) {
                out.add(new TermRange(t.getStartDate(), t.getEndDate()));
            }
        }
        return out;
    }
}
