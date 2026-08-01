package com.myplus.education.controller;

import java.util.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.Promotion;
import com.myplus.education.repository.PromotionRepository;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.service.PromotionService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 1.6 — promotion.
 * Design: microservices/docs/slices/edu-1.6-promotion.md
 *
 * <p>Pattern: <b>dry-run command</b>. {@code /getPromotionPlan} stores nothing; {@code /runPromotion}
 * applies decisions the admin has reviewed. This is the most destructive operation in the product — it
 * rewrites the class of every child in a roster — so the split is not a nicety.
 *
 * <p>Writes are {@code ADMIN_PRIVILEGE}, alongside fee settings and report-card publishing.
 */
@Controller
public class PromotionController {

    @Autowired private PromotionService promotionService;
    @Autowired private PromotionRepository promotionRepository;
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

    /** The JSON body of a run: the reviewed decisions, one per student. */
    public static class RunRequest {
        private Long academicYearId;
        private Long fromGradeId;
        private Long toGradeId;
        private List<Row> rows;

        public Long getAcademicYearId() { return academicYearId; }
        public void setAcademicYearId(Long v) { this.academicYearId = v; }
        public Long getFromGradeId() { return fromGradeId; }
        public void setFromGradeId(Long v) { this.fromGradeId = v; }
        public Long getToGradeId() { return toGradeId; }
        public void setToGradeId(Long v) { this.toGradeId = v; }
        public List<Row> getRows() { return rows; }
        public void setRows(List<Row> v) { this.rows = v; }

        public static class Row {
            private String enrollNo;
            private String outcome;
            public String getEnrollNo() { return enrollNo; }
            public void setEnrollNo(String v) { this.enrollNo = v; }
            public String getOutcome() { return outcome; }
            public void setOutcome(String v) { this.outcome = v; }
        }
    }

    // ── the plan (a read: computes, stores nothing) ─────────────────────────────────────────────

    /**
     * What WOULD happen. Ungated beyond authentication and scoping, consistent with every other education
     * read — the destructive step is the one that carries the privilege check.
     */
    @RequestMapping(value = "/getPromotionPlan", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getPromotionPlan(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long yearId = parseLong(request.getParameter("academicYearId"));
            if (yearId == null) return new GenericResponse("ERROR", "Academic year is required");
            Long fromGradeId = parseLong(request.getParameter("fromGradeId"));
            Long toGradeId = parseLong(request.getParameter("toGradeId"));

            List<PromotionService.PlanRow> plan =
                    promotionService.plan(org, uid, yearId, fromGradeId, toGradeId);

            List<Map<String, Object>> rows = new ArrayList<>();
            int undecided = 0, alreadyDecided = 0;
            for (PromotionService.PlanRow r : plan) {
                if (r.undecided()) undecided++;
                if (r.alreadyDecided()) alreadyDecided++;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("enrollNo", r.enrollNo());
                m.put("name", r.name());
                m.put("yearPercent", r.yearPercent());
                m.put("proposed", r.proposed());
                m.put("reason", r.reason());
                m.put("undecided", r.undecided());
                m.put("alreadyDecided", r.alreadyDecided());
                rows.add(m);
            }

            PromotionService.PlanRow first = plan.isEmpty() ? null : plan.get(0);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rows", rows);
            out.put("total", rows.size());
            // Surfaced as counts so the screen can say "3 students need a decision" BEFORE the button is
            // pressed, rather than presenting a mostly-blank plan and letting the admin discover it.
            out.put("undecided", undecided);
            out.put("alreadyDecided", alreadyDecided);
            out.put("graduating", toGradeId == null);
            out.put("requirePass", promotionService.policyConfig().requirePass());
            out.put("minPercent", promotionService.policyConfig().minPercent());
            if (first == null) out.put("empty", true);
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** One student's progression across years — reads the RECORD, never re-derives it. */
    @RequestMapping(value = "/getPromotionHistory", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getPromotionHistory(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String enrollNo = request.getParameter("enrollNo");
            Long yearId = parseLong(request.getParameter("academicYearId"));

            List<Promotion> found = StringUtils.hasText(enrollNo)
                    ? promotionRepository.findByStudentScoped(enrollNo.trim(), org, uid)
                    : (yearId == null ? List.of() : promotionRepository.findByYearScoped(yearId, org, uid));

            List<Map<String, Object>> out = new ArrayList<>();
            for (Promotion p : found) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.getId());
                m.put("enrollNo", p.getStudentEnrollNo());
                m.put("studentName", p.getStudentName());
                // The stored NAMES, not a join — a class renamed since must not retitle this record (D3).
                m.put("fromGradeName", p.getFromGradeName());
                m.put("toGradeName", p.getToGradeName());
                m.put("academicYearName", p.getAcademicYearName());
                m.put("outcome", p.getOutcome() == null ? null : p.getOutcome().name());
                m.put("status", p.getStatus() == null ? null : p.getStatus().name());
                m.put("reason", p.getReason());
                m.put("overridden", p.isOverridden());
                m.put("dated", p.getDated() == null ? null : p.getDated().toString());
                out.add(m);
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ── the run (ADMIN tier — it rewrites a whole class) ────────────────────────────────────────

    /**
     * Apply the reviewed decisions. JSON, because the body is a row list — the same shape as
     * {@code saveMarksBulk}.
     *
     * <p>Reports what actually happened per outcome rather than a blanket SUCCESS: a run that skipped
     * half the class because those students were already decided is not the same as one that promoted
     * them, and the admin must be able to tell.
     */
    @RequestMapping(value = "/runPromotion", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse runPromotion(@RequestBody RunRequest req) {
        try {
            Long org = orgId(), uid = userId();
            if (req == null || req.getAcademicYearId() == null) {
                return new GenericResponse("ERROR", "Academic year is required");
            }
            if (req.getRows() == null || req.getRows().isEmpty()) {
                return new GenericResponse("ERROR", "No students were selected");
            }

            List<PromotionService.Decision> decisions = new ArrayList<>();
            for (RunRequest.Row r : req.getRows()) {
                if (r == null || !StringUtils.hasText(r.getEnrollNo())) continue;
                if (!StringUtils.hasText(r.getOutcome())) continue;   // undecided rows are simply not sent
                decisions.add(new PromotionService.Decision(r.getEnrollNo(), r.getOutcome()));
            }
            if (decisions.isEmpty()) {
                return new GenericResponse("ERROR", "No decisions were made");
            }

            PromotionService.Result result = promotionService.run(
                    org, uid, req.getAcademicYearId(), req.getFromGradeId(), req.getToGradeId(), decisions);

            auditService.record("PROMOTION_RUN", "Promotion", String.valueOf(req.getAcademicYearId()),
                    "from=" + req.getFromGradeId() + " to=" + req.getToGradeId()
                            + " promoted=" + result.promoted() + " retained=" + result.retained()
                            + " graduated=" + result.graduated() + " skipped=" + result.skipped());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("promoted", result.promoted());
            out.put("retained", result.retained());
            out.put("graduated", result.graduated());
            out.put("skipped", result.skipped());
            out.put("problems", result.problems());

            String msg = result.promoted() + " promoted, " + result.retained() + " retained"
                    + (result.graduated() > 0 ? ", " + result.graduated() + " graduated" : "")
                    + (result.skipped() > 0 ? ", " + result.skipped() + " skipped" : "");
            // PARTIAL when anything did not go through, so the UI cannot round a partial run up to a
            // clean success — the same honesty 1.3 D3 built into bulk marks.
            String status = result.problems().isEmpty() ? "SUCCESS" : "PARTIAL";
            return new GenericResponse(status, msg, out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Undo one decision: restore the class, keep the row as REVERSED (D7). */
    @RequestMapping(value = "/undoPromotion", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse undoPromotion(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long id = parseLong(request.getParameter("id"));
            if (id == null) return new GenericResponse("ERROR", "Promotion is required");

            String problem = promotionService.undo(org, uid, id);
            if (problem != null) return new GenericResponse("FAILED", problem);

            auditService.record("PROMOTION_REVERSED", "Promotion", String.valueOf(id), "undo");
            return new GenericResponse("SUCCESS", "Promotion reversed");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }
}
