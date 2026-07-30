package com.myplus.education.controller;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.dto.FeeCollectionDTO;
import com.myplus.education.entity.FeeCollection;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.FeeCollectionRepository;
import com.myplus.education.repository.StudentRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;
import com.myplus.education.util.ScopedDeleter;

/**
 * Flat (legacy) Fee Collection endpoints — core list/add/delete. userId-scoped.
 * NOTE: loadFV (fee voucher), loadFL/loadFR (ledger/receipt) and findFc are deferred to a focused
 * follow-up (voucher computation + student/grade joins).
 */
@Controller
public class FeeCollectionController {

    @Autowired
    private FeeCollectionRepository feeCollectionRepository;
    @Autowired
    private StudentRepository studentRepository;   // P4: resolve visible students' enrollNos for branch scoping
    @Autowired
    private com.myplus.education.service.FeeService feeService;   // reads the org's fee-branch-scope policy
    @Autowired
    private RequestUtil requestUtil;

    @Autowired
    private ScopedDeleter scopedDeleter;   // anti-IDOR bulk delete
    @Autowired
    private AppUtil appUtil;

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    /** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    private FeeCollectionDTO toDto(FeeCollection o) {
        FeeCollectionDTO dto = new FeeCollectionDTO();
        dto.setId(o.getId());
        dto.setUserId(o.getUserId());
        dto.setEn(o.getEn());
        dto.setDt(o.getDt());
        dto.setD(o.getD());
        dto.setDd(o.getDd());
        dto.setDa(o.getDa());
        dto.setF(o.getF());
        dto.setFp(o.getFp());
        dto.setOd(o.getOd());
        dto.setOdd(o.getOdd());
        dto.setP(o.getP());
        dto.setRb(o.getRb());
        dto.setRi(o.getRi());
        dto.setPdStr(appUtil.getLocalDateStr(o.getPd()));
        return dto;
    }

    /**
     * Fee-collection visibility. By org policy (FeeSetting.feeCollectionBranchScoped, default FALSE) a fee can
     * be viewed/collected from ANY branch — a parent may pay at any campus — so the default is org-wide. Only
     * when the owner opts INTO branch scoping does a fee become visible solely to the student's branch (a fee
     * is for a student, resolved by enrollNo). Owner/super always see org-wide.
     */
    private List<FeeCollection> branchVisible(List<FeeCollection> rows) {
        if (!Boolean.TRUE.equals(feeService.settingFor(orgId(), userId()).getFeeCollectionBranchScoped()))
            return rows;   // org-wide (the default): fees are collectible at any branch
        if (requestUtil.isOwnerSuper()) return rows;
        java.util.Set<Long> schools = requestUtil.accessibleSchoolIds();
        if (schools.isEmpty()) return rows;
        java.util.Set<String> visibleEn = studentRepository.findScopedBySchools(orgId(), schools).stream()
                .map(Student::getEnrollNo).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return rows.stream().filter(f -> f.getEn() == null || visibleEn.contains(f.getEn()))
                .collect(Collectors.toList());
    }

    @RequestMapping(value = "/getUserFc", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserFc(final HttpServletRequest request) {
        try {
            List<FeeCollection> objs = branchVisible(feeCollectionRepository.findScoped(orgId(), userId()));
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getAllFc", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllFc(final HttpServletRequest request) {
        try {
            // Tenant- AND branch-scoped: a branch-constrained caller sees only their branches' fee records.
            List<FeeCollection> all = branchVisible(feeCollectionRepository.findScoped(orgId(), userId()));
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addFc", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addFc(final FeeCollectionDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();
            // Anti-IDOR: an edit names a fee record by a client-supplied id, so it must be resolved WITHIN
            // the caller's tenant. A bare findById followed by the setOrganizationId below would have moved
            // another school's PAYMENT RECORD into this one — the money row leaves its owner's books.
            FeeCollection obj;
            if (dto.getId() != null) {
                obj = feeCollectionRepository.findByIdScoped(dto.getId(), orgId, userId).orElse(null);
                if (obj == null) return new GenericResponse("NOT_FOUND", "Fee record not found");
            } else {
                obj = new FeeCollection();
            }
            obj.setUserId(userId);              // audit: who created/edited
            obj.setOrganizationId(orgId);       // tenant scope
            obj.setEn(dto.getEn());
            obj.setDt(dto.getDt());
            obj.setD(dto.getD());
            obj.setDd(dto.getDd());
            obj.setDa(dto.getDa());
            obj.setF(dto.getF());
            obj.setFp(dto.getFp());
            obj.setOd(dto.getOd());
            obj.setOdd(dto.getOdd());
            obj.setP(dto.getP());
            obj.setRb(dto.getRb());
            obj.setRi(dto.getRi());
            if (!appUtil.isEmptyOrNull(dto.getPdStr())) {
                obj.setPd(appUtil.getLocalDate(dto.getPdStr()));
            }
            FeeCollection saved = feeCollectionRepository.save(obj);
            return appUtil.isEmptyOrNull(saved)
                    ? new GenericResponse("FAILED", "")
                    : new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @RequestMapping(value = "/deleteFc", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteFc(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                // Anti-IDOR: only rows in the caller's own tenant are deleted (see ScopedDeleter).
                scopedDeleter.deleteScoped(feeCollectionRepository, ids,
                        FeeCollection::getOrganizationId, FeeCollection::getUserId, null);
                return true;
            }
            return false;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return false;
        }
    }
}
