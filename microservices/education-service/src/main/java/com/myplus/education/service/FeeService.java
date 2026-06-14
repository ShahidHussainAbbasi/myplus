package com.myplus.education.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.education.entity.Discount;
import com.myplus.education.entity.FeeCollection;
import com.myplus.education.entity.FeeSetting;
import com.myplus.education.entity.Grade;
import com.myplus.education.entity.Guardian;
import com.myplus.education.entity.School;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.DiscountRepository;
import com.myplus.education.repository.FeeCollectionRepository;
import com.myplus.education.repository.FeeSettingRepository;
import com.myplus.education.repository.GradeRepository;
import com.myplus.education.repository.GuardianRepository;
import com.myplus.education.repository.SchoolRepository;
import com.myplus.education.repository.StudentRepository;

import lombok.RequiredArgsConstructor;

/**
 * Fee computation shared by the voucher endpoint and the student-registration dues hook.
 * Multi-month aging: a student's due accrues monthly fee per unpaid month plus any carried balance.
 * Everything is tenant-scoped (the caller passes orgId/userId resolved from the request).
 */
@Service
@RequiredArgsConstructor
public class FeeService {

    private final StudentRepository studentRepository;
    private final GradeRepository gradeRepository;
    private final DiscountRepository discountRepository;
    private final FeeCollectionRepository feeCollectionRepository;
    private final FeeSettingRepository feeSettingRepository;
    private final SchoolRepository schoolRepository;
    private final GuardianRepository guardianRepository;

    /** The org's fee policy, or sensible defaults if none saved yet. */
    public FeeSetting settingFor(Long orgId, Long userId) {
        return feeSettingRepository.findByOrganizationId(orgId)
                .orElseGet(() -> FeeSetting.builder().organizationId(orgId).userId(userId).build());
    }

    /** Monthly due for a student = base fee (student override else grade fee) + vehicle fare − discount. */
    public int monthlyDue(Student s) {
        double base = s.getFee() != null ? s.getFee() : gradeFee(s.getGradeId());
        double vehicle = s.getVf() != null ? s.getVf() : 0;
        double discount = discountAmount(s.getDiscountId(), base);
        return (int) Math.round(Math.max(base + vehicle - discount, 0));
    }

    /** Aged voucher for one student (faithful to legacy: charge dueMonths when &gt; 1, plus carried balance). */
    public Map<String, Object> voucherForStudent(Long orgId, Student s, boolean aging) {
        int monthly = monthlyDue(s);
        List<FeeCollection> fcs = feeCollectionRepository
                .findByOrganizationIdAndEnOrderByIdAsc(orgId, s.getEnrollNo());
        LocalDate lpd = fcs.stream().map(FeeCollection::getPd).filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(s.getEnrollDate() != null ? s.getEnrollDate() : LocalDate.now());
        int dm = monthsBetween(lpd, LocalDate.now());
        int months = aging ? (dm > 1 ? dm : 1) : 1;
        int prevBalance = fcs.isEmpty() ? 0
                : (fcs.get(fcs.size() - 1).getDb() == null ? 0 : fcs.get(fcs.size() - 1).getDb());
        int total = monthly * months + prevBalance;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enrollNo", s.getEnrollNo());
        m.put("studentName", s.getName());
        m.put("gradeName", gradeName(s.getGradeId()));
        m.put("schoolName", schoolName(s.getSchoolId()));
        m.put("guardianName", guardianName(s.getGuardianId()));
        m.put("monthlyDue", monthly);
        m.put("dueMonths", months);
        m.put("previousBalance", prevBalance);
        m.put("totalDue", total);
        return m;
    }

    /** Consolidated voucher across all of a guardian's students (org-scoped). */
    public Map<String, Object> voucherForGuardian(Long orgId, Long userId, Long guardianId, boolean aging) {
        List<Map<String, Object>> lines = new ArrayList<>();
        int total = 0;
        String guardianName = guardianName(guardianId);
        for (Student s : studentRepository.findScoped(orgId, userId)) {
            if (!Objects.equals(s.getGuardianId(), guardianId)) continue;
            Map<String, Object> v = voucherForStudent(orgId, s, aging);
            lines.add(v);
            total += (int) v.get("totalDue");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("guardianId", guardianId);
        out.put("guardianName", guardianName);
        out.put("lines", lines);
        out.put("totalDue", total);
        return out;
    }

    /**
     * Create the opening due record for a freshly registered student (if not already present).
     * Stored as an unpaid row (fp=0) so the student shows as owing; carried balance (db) stays 0 so
     * the voucher's aging doesn't double-count.
     */
    @Transactional
    public void registerOpeningDue(Long orgId, Long userId, Student s) {
        if (s == null || s.getEnrollNo() == null || s.getEnrollNo().isBlank()) return;
        List<FeeCollection> existing = feeCollectionRepository
                .findByOrganizationIdAndEnOrderByIdAsc(orgId, s.getEnrollNo());
        if (!existing.isEmpty()) return;
        int monthly = monthlyDue(s);
        FeeCollection fc = new FeeCollection();
        fc.setOrganizationId(orgId);
        fc.setUserId(userId);
        fc.setEn(s.getEnrollNo());
        fc.setF(monthly);
        fc.setFp(0);
        fc.setDa(monthly);
        fc.setDb(0);
        fc.setPd(s.getEnrollDate() != null ? s.getEnrollDate() : LocalDate.now());
        fc.setRi("OPENING_DUE");
        feeCollectionRepository.save(fc);
    }

    // ---- helpers ----
    private double gradeFee(Long gradeId) {
        if (gradeId == null) return 0;
        Grade g = gradeRepository.findById(gradeId).orElse(null);
        return g != null && g.getFee() != null ? g.getFee() : 0;
    }

    private double discountAmount(Long discountId, double base) {
        if (discountId == null) return 0;
        Discount d = discountRepository.findById(discountId).orElse(null);
        if (d == null || d.getAmount() == null) return 0;
        return "%".equals(d.getDi()) ? base * d.getAmount() / 100.0 : d.getAmount();
    }

    public String gradeName(Long gradeId) {
        if (gradeId == null) return "";
        Grade g = gradeRepository.findById(gradeId).orElse(null);
        return g == null ? "" : g.getName();
    }

    public String schoolName(Long schoolId) {
        if (schoolId == null) return "";
        School s = schoolRepository.findById(schoolId).orElse(null);
        return s == null ? "" : s.getBranchName();
    }

    public String guardianName(Long guardianId) {
        if (guardianId == null) return "";
        Guardian g = guardianRepository.findById(guardianId).orElse(null);
        return g == null ? "" : g.getName();
    }

    private int monthsBetween(LocalDate a, LocalDate b) {
        int m = (b.getYear() - a.getYear()) * 12 + (b.getMonthValue() - a.getMonthValue());
        return Math.max(m, 0);
    }
}
