package com.myplus.education.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.AcademicYear;
import com.myplus.education.entity.Term;
import com.myplus.education.repository.AcademicYearRepository;
import com.myplus.education.repository.TermRepository;
import com.myplus.education.service.TermService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;
import com.myplus.education.util.ScopedDeleter;

/**
 * Slice 1.1 — academic years and their terms.
 * Design: microservices/docs/slices/edu-1.1-academic-year-term.md
 *
 * Privilege tier (D-3): this is STRUCTURE, not daily work, so writes are ADMIN_PRIVILEGE — the same
 * tier as grades, fee settings and school setup. Reads stay open to any authenticated user because
 * every screen that shows a term needs to name it.
 */
@Controller
public class AcademicYearController {

    private static final DateTimeFormatter UI_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    @Autowired private AcademicYearRepository academicYearRepository;
    @Autowired private TermRepository termRepository;
    @Autowired private TermService termService;
    @Autowired private RequestUtil requestUtil;
    @Autowired private ScopedDeleter scopedDeleter;
    @Autowired private AppUtil appUtil;

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    /** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    /** Wire format is dd-MM-yyyy — the date-picker contract (see /js/common/date-picker.js). */
    private static LocalDate parseDate(String s) {
        if (!StringUtils.hasText(s)) return null;
        try {
            return LocalDate.parse(s.trim(), UI_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmt(LocalDate d) {
        return d == null ? null : d.format(UI_DATE);
    }

    private Map<String, Object> yearDto(AcademicYear y, List<Term> terms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", y.getId());
        m.put("name", y.getName());
        m.put("startDateStr", fmt(y.getStartDate()));
        m.put("endDateStr", fmt(y.getEndDate()));
        m.put("status", y.getStatus());
        List<Map<String, Object>> ts = new ArrayList<>();
        for (Term t : terms) ts.add(termDto(t));
        m.put("terms", ts);
        return m;
    }

    private Map<String, Object> termDto(Term t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("academicYearId", t.getAcademicYearId());
        m.put("name", t.getName());
        m.put("sequence", t.getSequence());
        m.put("startDateStr", fmt(t.getStartDate()));
        m.put("endDateStr", fmt(t.getEndDate()));
        m.put("pinnedCurrent", t.isPinnedCurrent());
        return m;
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────────

    /** Every year for the tenant, each with its terms nested. */
    @RequestMapping(value = "/getAcademicYears", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAcademicYears(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            List<AcademicYear> years = academicYearRepository.findScoped(org, uid);
            List<Map<String, Object>> out = new ArrayList<>();
            for (AcademicYear y : years) {
                out.add(yearDto(y, termRepository.findByYearScoped(y.getId(), org, uid)));
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * The current term, or a SUCCESS with a null object when the school has defined none.
     * NOT_FOUND would read as an error; "no terms yet" is a legitimate, permanent state (D3/§7).
     */
    @RequestMapping(value = "/getCurrentTerm", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getCurrentTerm(final HttpServletRequest request) {
        try {
            Term t = termService.currentTerm(orgId(), userId());
            return new GenericResponse("SUCCESS", "", t == null ? null : termDto(t));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── writes (ADMIN tier — structure) ─────────────────────────────────────────────────────────

    @RequestMapping(value = "/addAcademicYear", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public GenericResponse addAcademicYear(final HttpServletRequest request) {
        try {
            String name = request.getParameter("name");
            if (!StringUtils.hasText(name)) return new GenericResponse("ERROR", "Year name is required");

            Long org = orgId(), uid = userId();
            String idStr = request.getParameter("id");
            AcademicYear y;
            if (StringUtils.hasText(idStr)) {
                // Anti-IDOR: an unscoped findById here would let a caller re-parent another tenant's row.
                y = academicYearRepository.findByIdScoped(Long.valueOf(idStr.trim()), org, uid).orElse(null);
                if (y == null) return new GenericResponse("ERROR", "Academic year not found");
            } else {
                y = AcademicYear.builder().userId(uid).organizationId(org)
                        .dated(LocalDateTime.now()).status("Active").build();
            }
            y.setName(name.trim());
            y.setStartDate(parseDate(request.getParameter("startDateStr")));
            y.setEndDate(parseDate(request.getParameter("endDateStr")));
            y.setUpdated(LocalDateTime.now());
            academicYearRepository.save(y);
            return new GenericResponse("SUCCESS", "Academic year saved");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addTerm", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public GenericResponse addTerm(final HttpServletRequest request) {
        try {
            String name = request.getParameter("name");
            String yearIdStr = request.getParameter("academicYearId");
            if (!StringUtils.hasText(name)) return new GenericResponse("ERROR", "Term name is required");
            if (!StringUtils.hasText(yearIdStr)) return new GenericResponse("ERROR", "Academic year is required");

            Long org = orgId(), uid = userId();
            // The parent year must belong to this tenant — otherwise a term could be hung off another
            // school's year, which is the save-takeover shape finding A was about.
            if (academicYearRepository.findByIdScoped(Long.valueOf(yearIdStr.trim()), org, uid).isEmpty()) {
                return new GenericResponse("ERROR", "Academic year not found");
            }

            String idStr = request.getParameter("id");
            Term t;
            if (StringUtils.hasText(idStr)) {
                t = termRepository.findByIdScoped(Long.valueOf(idStr.trim()), org, uid).orElse(null);
                if (t == null) return new GenericResponse("ERROR", "Term not found");
            } else {
                t = Term.builder().userId(uid).organizationId(org).dated(LocalDateTime.now()).build();
            }
            t.setAcademicYearId(Long.valueOf(yearIdStr.trim()));
            t.setName(name.trim());
            String seq = request.getParameter("sequence");
            t.setSequence(StringUtils.hasText(seq) ? Integer.valueOf(seq.trim()) : null);
            t.setStartDate(parseDate(request.getParameter("startDateStr")));
            t.setEndDate(parseDate(request.getParameter("endDateStr")));
            t.setUpdated(LocalDateTime.now());
            termRepository.save(t);
            return new GenericResponse("SUCCESS", "Term saved");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Pin (or unpin) a term as current — the D3 override. Pinning one term unpins the others for the
     * tenant, so "pinned" can never be ambiguous in the data.
     */
    @RequestMapping(value = "/pinCurrentTerm", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public GenericResponse pinCurrentTerm(final HttpServletRequest request) {
        try {
            String idStr = request.getParameter("id");
            if (!StringUtils.hasText(idStr)) return new GenericResponse("ERROR", "Term is required");
            Long org = orgId(), uid = userId();
            Term target = termRepository.findByIdScoped(Long.valueOf(idStr.trim()), org, uid).orElse(null);
            if (target == null) return new GenericResponse("ERROR", "Term not found");

            boolean pin = !"false".equalsIgnoreCase(request.getParameter("pinned"));
            List<Term> all = termRepository.findScoped(org, uid);
            List<Term> changed = new ArrayList<>();
            for (Term t : all) {
                boolean want = pin && t.getId().equals(target.getId());
                if (t.isPinnedCurrent() != want) {
                    t.setPinnedCurrent(want);
                    t.setUpdated(LocalDateTime.now());
                    changed.add(t);
                }
            }
            if (!changed.isEmpty()) termRepository.saveAll(changed);   // one batch, not a save per row
            return new GenericResponse("SUCCESS", pin ? "Term pinned as current" : "Term unpinned");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── deletes (DELETE tier) ───────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/deleteTerm", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    public GenericResponse deleteTerm(final HttpServletRequest request) {
        try {
            scopedDeleter.deleteScoped(termRepository, request.getParameter("checked"),
                    Term::getOrganizationId, Term::getUserId, null);
            return new GenericResponse("SUCCESS", "Deleted");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteAcademicYear", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    public GenericResponse deleteAcademicYear(final HttpServletRequest request) {
        try {
            scopedDeleter.deleteScoped(academicYearRepository, request.getParameter("checked"),
                    AcademicYear::getOrganizationId, AcademicYear::getUserId, null);
            return new GenericResponse("SUCCESS", "Deleted");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }
}
