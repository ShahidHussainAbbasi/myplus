package com.myplus.education.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.myplus.education.entity.GradeBand;
import com.myplus.education.repository.GradeBandRepository;
import com.myplus.education.service.BandValidator;
import com.myplus.education.service.GradingService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 1.4 — the grading scale.
 * Design: microservices/docs/slices/edu-1.4-grading-scales.md
 *
 * Privilege tier (D-3): a grading scale decides what every result MEANS, so writes are ADMIN_PRIVILEGE —
 * the policy tier, alongside fee settings. Reads stay open because every screen showing a grade needs to
 * name it.
 */
@Controller
public class GradingController {

    @Autowired private GradeBandRepository gradeBandRepository;
    @Autowired private GradingService gradingService;
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

    private static Integer parseInt(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return Integer.valueOf(s.trim()); } catch (Exception e) { return null; }
    }

    private Map<String, Object> bandDto(GradeBand b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("name", b.getName());
        m.put("minPercent", b.getMinPercent());
        m.put("maxPercent", b.getMaxPercent());
        m.put("gpaPoints", b.getGpaPoints());
        return m;
    }

    /**
     * The whole scale plus the policies that go with it — one call, because a screen showing bands always
     * wants to show the policies too, and a second round trip buys nothing.
     */
    @RequestMapping(value = "/getGradingScale", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getGradingScale(final HttpServletRequest request) {
        try {
            List<Map<String, Object>> bands = new ArrayList<>();
            for (GradeBand b : gradingService.scale(orgId(), userId())) bands.add(bandDto(b));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("bands", bands);
            out.put("absentCountsAsZero", gradingService.absentCountsAsZero());
            // An empty scale is a legitimate state, not an error (D2) — say so explicitly so the UI can
            // offer the preset rather than rendering an empty table with no explanation.
            out.put("configured", !bands.isEmpty());
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Add or edit one band, then validate the WHOLE scale (D5) — a band is only correct relative to its
     * neighbours, so saving one row in isolation cannot tell you whether the scale still works.
     */
    @RequestMapping(value = "/saveGradeBand", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse saveGradeBand(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String name = request.getParameter("name");
            if (!StringUtils.hasText(name)) return new GenericResponse("ERROR", "Band name is required");

            String idStr = request.getParameter("id");
            GradeBand band;
            if (StringUtils.hasText(idStr)) {
                // Anti-IDOR: an unscoped findById here would let a caller re-band another tenant's scale.
                band = gradeBandRepository.findByIdScoped(Long.valueOf(idStr.trim()), org, uid).orElse(null);
                if (band == null) return new GenericResponse("NOT_FOUND", "Band not found");
            } else {
                band = GradeBand.builder().userId(uid).organizationId(org).dated(LocalDateTime.now()).build();
            }
            band.setName(name.trim());
            band.setMinPercent(parseInt(request.getParameter("minPercent")));
            band.setMaxPercent(parseInt(request.getParameter("maxPercent")));
            String gpa = request.getParameter("gpaPoints");
            band.setGpaPoints(StringUtils.hasText(gpa) ? Double.valueOf(gpa.trim()) : null);
            band.setUpdated(LocalDateTime.now());

            // Validate the scale AS IT WOULD BE after this save, without writing first.
            List<GradeBand> proposed = new ArrayList<>();
            for (GradeBand existing : gradeBandRepository.findScoped(org, uid)) {
                if (band.getId() != null && band.getId().equals(existing.getId())) continue;   // replaced below
                proposed.add(existing);
            }
            proposed.add(band);

            List<String> problems = BandValidator.validateSet(proposed);
            if (!problems.isEmpty()) {
                return new GenericResponse("FAILED", String.join("; ", problems));
            }
            gradeBandRepository.save(band);
            return new GenericResponse("SUCCESS", "Grading band saved");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Delete a band. The remaining scale is NOT re-validated: removing a band necessarily leaves a gap,
     * and refusing that would make a scale impossible to rebuild once created. An incomplete scale simply
     * grades nothing in the missing range (D2), which the screen shows.
     */
    @RequestMapping(value = "/deleteGradeBand", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @Transactional
    public GenericResponse deleteGradeBand(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            String checked = request.getParameter("checked");
            if (!StringUtils.hasText(checked)) return new GenericResponse("SUCCESS", "Nothing to delete");
            int n = 0;
            for (String raw : checked.split(",")) {
                if (!StringUtils.hasText(raw)) continue;
                GradeBand b = gradeBandRepository.findByIdScoped(Long.valueOf(raw.trim()), org, uid).orElse(null);
                if (b == null) continue;   // not this tenant's — skip silently, as ScopedDeleter does
                gradeBandRepository.delete(b);
                n++;
            }
            return new GenericResponse("SUCCESS", n + " band(s) deleted");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * One-click preset. Explicitly chosen by the owner, never seeded (D2): seeding would impose a
     * jurisdiction the platform deliberately does not know. Refused when a scale already exists, so it
     * cannot silently overwrite a school's own bands.
     */
    @RequestMapping(value = "/applyGradingPreset", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse applyGradingPreset(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            if (!gradeBandRepository.findScoped(org, uid).isEmpty()) {
                return new GenericResponse("FAILED",
                        "A grading scale already exists. Delete the existing bands first if you want to replace it.");
            }
            // A common five-band scale. Offered, not assumed — a school is free to delete and rebuild it.
            int[][] ranges = { {0, 32}, {33, 49}, {50, 59}, {60, 79}, {80, 100} };
            String[] names = { "F", "D", "C", "B", "A" };
            double[] points = { 0.0, 1.0, 2.0, 3.0, 4.0 };
            List<GradeBand> preset = new ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                preset.add(GradeBand.builder()
                        .name(names[i]).minPercent(ranges[i][0]).maxPercent(ranges[i][1]).gpaPoints(points[i])
                        .userId(uid).organizationId(org)
                        .dated(LocalDateTime.now()).updated(LocalDateTime.now())
                        .build());
            }
            gradeBandRepository.saveAll(preset);   // one batch, not a save per band
            return new GenericResponse("SUCCESS", preset.size() + " bands created");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }
}
