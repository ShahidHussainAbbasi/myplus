package com.myplus.education.controller;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.FeeCollection;
import com.myplus.education.entity.FeeSetting;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.FeeCollectionRepository;
import com.myplus.education.repository.FeeSettingRepository;
import com.myplus.education.repository.StudentRepository;
import com.myplus.education.service.FeeService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Fee configuration (runtime, per org), report, ledger, and aged voucher. All org-scoped.
 * Built fresh (not a monolith port). Feeds the income side of org analytics.
 */
@Controller
public class FeeController {

    @Autowired private FeeService feeService;
    @Autowired private FeeSettingRepository feeSettingRepository;
    @Autowired private FeeCollectionRepository feeCollectionRepository;
    @Autowired private StudentRepository studentRepository;
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

    // ---- Fee settings (runtime config) ----

    @RequestMapping(value = "/getFeeSetting", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getFeeSetting() {
        try {
            FeeSetting s = feeService.settingFor(orgId(), userId());
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("feeCycle", s.getFeeCycle());
            o.put("dueDay", s.getDueDay());
            o.put("agingEnabled", Boolean.TRUE.equals(s.getAgingEnabled()));
            o.put("autoRegisterDues", Boolean.TRUE.equals(s.getAutoRegisterDues()));
            o.put("paymentMode", s.getPaymentMode());
            return new GenericResponse("SUCCESS", "", o);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/saveFeeSetting", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public GenericResponse saveFeeSetting(@RequestParam(required = false) String feeCycle,
                                          @RequestParam(required = false) Integer dueDay,
                                          @RequestParam(required = false) Boolean agingEnabled,
                                          @RequestParam(required = false) Boolean autoRegisterDues,
                                          @RequestParam(required = false) String paymentMode) {
        try {
            Long org = orgId();
            FeeSetting s = feeSettingRepository.findByOrganizationId(org)
                    .orElseGet(() -> FeeSetting.builder().organizationId(org).build());
            s.setUserId(userId());
            if (feeCycle != null) s.setFeeCycle(feeCycle);
            if (dueDay != null) s.setDueDay(dueDay);
            if (agingEnabled != null) s.setAgingEnabled(agingEnabled);
            if (autoRegisterDues != null) s.setAutoRegisterDues(autoRegisterDues);
            if (paymentMode != null) s.setPaymentMode(paymentMode);
            feeSettingRepository.save(s);
            return new GenericResponse("SUCCESS", "Fee settings saved");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ---- Voucher (aged) ----

    @RequestMapping(value = "/loadFV", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse loadFV(@RequestParam(required = false) String enrollNo,
                                  @RequestParam(required = false) Long guardianId) {
        try {
            Long org = orgId();
            boolean aging = Boolean.TRUE.equals(feeService.settingFor(org, userId()).getAgingEnabled());
            if (guardianId != null) {
                return new GenericResponse("SUCCESS", "", feeService.voucherForGuardian(org, userId(), guardianId, aging));
            }
            if (appUtil.isEmptyOrNull(enrollNo)) {
                return new GenericResponse("INVALID", "Provide a student enroll no or a guardian");
            }
            Student s = studentRepository.findScoped(org, userId()).stream()
                    .filter(x -> enrollNo.equalsIgnoreCase(x.getEnrollNo())).findFirst().orElse(null);
            if (s == null) return new GenericResponse("NOT_FOUND", "Student not found");
            return new GenericResponse("SUCCESS", "", feeService.voucherForStudent(org, s, aging));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ---- Ledger (one student's history) ----

    @RequestMapping(value = "/loadFL", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse loadFL(@RequestParam(required = false) String enrollNo) {
        try {
            if (appUtil.isEmptyOrNull(enrollNo)) {
                return new GenericResponse("INVALID", "Provide a student enroll no");
            }
            Long org = orgId();
            Student s = studentRepository.findScoped(org, userId()).stream()
                    .filter(x -> enrollNo.equalsIgnoreCase(x.getEnrollNo())).findFirst().orElse(null);
            List<FeeCollection> rows = feeCollectionRepository.findByOrganizationIdAndEnOrderByIdAsc(org, enrollNo);

            Map<String, Object> header = new LinkedHashMap<>();
            header.put("enrollNo", enrollNo);
            header.put("studentName", s == null ? "" : s.getName());
            header.put("gradeName", s == null ? "" : feeService.gradeName(s.getGradeId()));
            header.put("schoolName", s == null ? "" : feeService.schoolName(s.getSchoolId()));
            header.put("guardianName", s == null ? "" : feeService.guardianName(s.getGuardianId()));
            int paid = rows.stream().mapToInt(r -> r.getFp() == null ? 0 : r.getFp()).sum();
            int fee = rows.stream().mapToInt(r -> r.getF() == null ? 0 : r.getF()).sum();
            header.put("totalFee", fee);
            header.put("totalPaid", paid);
            header.put("balance", fee - paid);

            List<Map<String, Object>> ledger = rows.stream().map(this::ledgerRow).collect(Collectors.toList());
            if (ledger.isEmpty()) return new GenericResponse("NOT_FOUND", "No fee records", ledger);
            GenericResponse gr = new GenericResponse("SUCCESS", "", ledger);
            gr.setObject(header);
            return gr;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ---- Report (org income, filtered + totals) ----

    @RequestMapping(value = "/loadFR", method = RequestMethod.POST)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse loadFR(final HttpServletRequest request) {
        try {
            Long org = orgId();
            String by = param(request, "by", "ALL");
            String id = param(request, "id", "");
            LocalDate from = appUtil.isEmptyOrNull(param(request, "fromStr", "")) ? null : appUtil.getLocalDate(request.getParameter("fromStr"));
            LocalDate to = appUtil.isEmptyOrNull(param(request, "toStr", "")) ? null : appUtil.getLocalDate(request.getParameter("toStr"));

            // Resolve the set of enroll-nos in scope (null = all).
            Set<String> scope = resolveScope(org, by, id);

            List<FeeCollection> all = feeCollectionRepository.findScoped(org, userId());
            Map<String, Student> byEn = studentRepository.findScoped(org, userId()).stream()
                    .filter(s -> s.getEnrollNo() != null)
                    .collect(Collectors.toMap(Student::getEnrollNo, s -> s, (a, b) -> a));

            List<Map<String, Object>> rows = new ArrayList<>();
            int tFee = 0, tDis = 0, tOd = 0, tDue = 0, tPaid = 0, tBal = 0;
            for (FeeCollection f : all) {
                if (scope != null && !scope.contains(f.getEn())) continue;
                if (from != null && (f.getPd() == null || f.getPd().isBefore(from))) continue;
                if (to != null && (f.getPd() == null || f.getPd().isAfter(to))) continue;
                Student s = byEn.get(f.getEn());
                int fee = i(f.getF()), paid = i(f.getFp()), dis = i(f.getD()), od = i(f.getOd()), da = i(f.getDa());
                int bal = fee - paid;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("enrollNo", f.getEn());
                r.put("studentName", s == null ? "" : s.getName());
                r.put("gradeName", s == null ? "" : feeService.gradeName(s.getGradeId()));
                r.put("schoolName", s == null ? "" : feeService.schoolName(s.getSchoolId()));
                r.put("paymentDateStr", appUtil.getLocalDateStr(f.getPd()));
                r.put("payee", f.getP());
                r.put("receivedBy", f.getRb());
                r.put("fee", fee);
                r.put("discount", dis);
                r.put("otherDues", od);
                r.put("dueAmount", da);
                r.put("feePaid", paid);
                r.put("balance", bal);
                rows.add(r);
                tFee += fee; tDis += dis; tOd += od; tDue += da; tPaid += paid; tBal += bal;
            }

            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("fee", tFee); totals.put("discount", tDis); totals.put("otherDues", tOd);
            totals.put("dueAmount", tDue); totals.put("feePaid", tPaid); totals.put("balance", tBal);
            totals.put("count", rows.size());

            if (rows.isEmpty()) return new GenericResponse("NOT_FOUND", "No fee records", rows);
            GenericResponse gr = new GenericResponse("SUCCESS", "", rows);
            gr.setObject(totals);
            return gr;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /** Enroll-nos in scope for the report; null means "all in the tenant". */
    private Set<String> resolveScope(Long org, String by, String id) {
        if (by == null || "ALL".equalsIgnoreCase(by) || appUtil.isEmptyOrNull(id)) {
            if ("STUDENT".equalsIgnoreCase(by) && !appUtil.isEmptyOrNull(id)) {
                return Set.of(id);
            }
            return null;
        }
        if ("STUDENT".equalsIgnoreCase(by)) return Set.of(id);
        List<Student> students = studentRepository.findScoped(org, userId());
        Long lid = parseLong(id);
        return students.stream().filter(s -> {
            if ("GUARDIAN".equalsIgnoreCase(by)) return Objects.equals(s.getGuardianId(), lid);
            if ("CLASS".equalsIgnoreCase(by)) return Objects.equals(s.getGradeId(), lid);
            if ("CAMPUS".equalsIgnoreCase(by)) return Objects.equals(s.getSchoolId(), lid);
            return false;
        }).map(Student::getEnrollNo).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private Map<String, Object> ledgerRow(FeeCollection f) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("paymentDateStr", appUtil.getLocalDateStr(f.getPd()));
        r.put("fee", i(f.getF()));
        r.put("discount", i(f.getD()));
        r.put("otherDues", i(f.getOd()));
        r.put("dueAmount", i(f.getDa()));
        r.put("feePaid", i(f.getFp()));
        r.put("balance", i(f.getF()) - i(f.getFp()));
        r.put("payee", f.getP());
        r.put("receivedBy", f.getRb());
        r.put("receivedIn", f.getRi());
        return r;
    }

    private int i(Integer v) { return v == null ? 0 : v; }
    private Long parseLong(String s) { try { return Long.valueOf(s.trim()); } catch (Exception e) { return null; } }
    private String param(HttpServletRequest r, String name, String def) {
        String v = r.getParameter(name);
        return (v == null || v.isBlank()) ? def : v;
    }
}
