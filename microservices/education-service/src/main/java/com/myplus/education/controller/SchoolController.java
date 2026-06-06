package com.myplus.education.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.dto.SchoolDTO;
import com.myplus.education.entity.Owner;
import com.myplus.education.entity.School;
import com.myplus.education.repository.OwnerRepository;
import com.myplus.education.repository.SchoolRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Flat (legacy) School endpoints consumed by the monolith education pages. Root-mapped so the
 * gateway's /api/education/** + StripPrefix=2 routes them here; returns {@link GenericResponse}.
 * Scoped by the caller's userId (tenancy by user, like business-service).
 */
@Controller
public class SchoolController {

    @Autowired
    private SchoolRepository schoolRepository;
    @Autowired
    private OwnerRepository ownerRepository;
    @Autowired
    private RequestUtil requestUtil;
    @Autowired
    private AppUtil appUtil;

    private Long userId() {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        return user == null ? null : user.getUserId();
    }

    /** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
    private Long orgId() {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        return user == null ? null : user.getOrganizationId();
    }

    private SchoolDTO toDto(School s, boolean withOwners) {
        SchoolDTO dto = new SchoolDTO();
        dto.setId(s.getId());
        dto.setUserId(s.getUserId());
        dto.setName(s.getName());
        dto.setBranchName(s.getBranchName());
        dto.setEmail(s.getEmail());
        dto.setPhone(s.getPhone());
        dto.setAddress(s.getAddress());
        dto.setStatus(s.getStatus());
        dto.setDatedStr(appUtil.getDateStr(s.getDated()));
        dto.setUpdatedStr(appUtil.getDateStr(s.getUpdated()));
        if (withOwners && s.getOwners() != null) {
            dto.setOwnerIds(s.getOwners().stream().map(Owner::getId).collect(Collectors.toSet()));
            dto.setOwnerNames(s.getOwners().stream().map(Owner::getName).collect(Collectors.toSet()));
        }
        return dto;
    }

    @RequestMapping(value = "/getMainBranchName", method = RequestMethod.GET)
    @ResponseBody
    public String getMainBranchName() {
        try {
            List<School> objs = schoolRepository.findScoped(orgId(), userId());
            return appUtil.isEmptyOrNull(objs) ? "" : objs.get(0).getName();
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return "";
        }
    }

    @RequestMapping(value = "/getUserSchool", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getUserSchool(final HttpServletRequest request) {
        try {
            List<School> objs = schoolRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            List<SchoolDTO> dtos = objs.stream().map(s -> toDto(s, true)).collect(Collectors.toList());
            return new GenericResponse("SUCCESS", "", dtos);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getUserSchools", method = RequestMethod.GET)
    @ResponseBody
    public String getUserSchools(final HttpServletRequest request) {
        StringBuffer sb = new StringBuffer();
        try {
            List<School> schools = schoolRepository.findScoped(orgId(), userId());
            if (!appUtil.isEmptyOrNull(schools) && schools.size() > 1) {
                sb.append("<option value=''>Nothing Selected</option>");
            }
            schools.forEach(d -> {
                if (d != null && d.getId() != null) {
                    sb.append("<option value=" + d.getId() + ">" + d.getBranchName() + "</option>");
                }
            });
        } catch (Exception e) {
            appUtil.le(getClass(), e);
        }
        return sb.toString();
    }

    @RequestMapping(value = "/getAllSchool", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getAllSchool(final HttpServletRequest request) {
        try {
            // Tenant-scoped: "all" means all branches in the active organization, not every tenant's.
            List<School> all = schoolRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "", new ArrayList<SchoolDTO>());
            }
            List<SchoolDTO> dtos = all.stream().map(s -> toDto(s, true)).collect(Collectors.toList());
            return new GenericResponse("SUCCESS", "", dtos);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addSchool", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public GenericResponse addSchool(final SchoolDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();
            // Duplicate branch check on create — scoped to the active tenant.
            if (appUtil.isEmptyOrNull(dto.getId())) {
                boolean exists = schoolRepository.findScoped(orgId, userId).stream()
                        .anyMatch(s -> s.getBranchName() != null && s.getBranchName().equalsIgnoreCase(dto.getBranchName()));
                if (exists) {
                    return new GenericResponse("FOUND", "The School branch '" + dto.getBranchName() + "' already exists");
                }
            }

            School obj = (dto.getId() != null)
                    ? schoolRepository.findById(dto.getId()).orElseGet(School::new)
                    : new School();
            obj.setUserId(userId);          // audit: who created/edited
            obj.setOrganizationId(orgId);   // tenant scope
            obj.setName(dto.getName());
            obj.setBranchName(dto.getBranchName());
            obj.setEmail(dto.getEmail());
            obj.setPhone(dto.getPhone());
            obj.setAddress(dto.getAddress());
            obj.setStatus(dto.getStatus());
            if (obj.getDated() == null) {
                obj.setDated(LocalDateTime.now());
            }
            obj.setUpdated(LocalDateTime.now());

            Set<Owner> owners = new HashSet<>();
            if (dto.getOwnerIds() != null) {
                for (Long id : dto.getOwnerIds()) {
                    if (!appUtil.isEmptyOrNull(id)) {
                        ownerRepository.findById(id).ifPresent(owners::add);
                    }
                }
            }
            obj.setOwners(owners);

            School saved = schoolRepository.save(obj);
            return appUtil.isEmptyOrNull(saved)
                    ? new GenericResponse("FAILED", "")
                    : new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteSchool", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public boolean deleteSchool(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                for (String id : ids.split(",")) {
                    if (!StringUtils.isEmpty(id)) {
                        schoolRepository.deleteById(Long.valueOf(id));
                    }
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return false;
        }
    }
}
