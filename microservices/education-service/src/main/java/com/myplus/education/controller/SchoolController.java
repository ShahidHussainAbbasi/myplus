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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.myplus.education.util.ScopedDeleter;

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
    private ScopedDeleter scopedDeleter;   // anti-IDOR bulk delete
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

    /**
     * P4 — the branches THIS caller may work at, for the branch switcher: their granted schools, or every school
     * in the org for an owner (grants never narrow an owner) / a caller with no grants (single-branch, unchanged).
     * The ACTIVE branch is flagged, because only the server knows which one the JWT carries.
     */
    @RequestMapping(value = "/getMySchools", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getMySchools() {
        try {
            java.util.Set<Long> mine = requestUtil.accessibleSchoolIds();
            Long active = requestUtil.activeSchoolId();
            List<SchoolDTO> dtos = schoolRepository.findScoped(orgId(), userId()).stream()
                    .filter(s -> requestUtil.isOwnerSuper() || mine.isEmpty() || mine.contains(s.getId()))
                    .map(s -> {
                        SchoolDTO dto = toDto(s, false);
                        dto.setActive(active != null && active.equals(s.getId()));
                        return dto;
                    })
                    .collect(Collectors.toList());
            return new GenericResponse("SUCCESS", "Branches loaded", dtos);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", "Could not load branches.");
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

    // D-3 privilege map: money / structure / policy — not routine data entry
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
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

            // Anti-IDOR: an edit names a branch by a client-supplied id, so it must be resolved WITHIN the
            // caller's tenant. A bare findById followed by the setOrganizationId below would have moved
            // another org's branch — and every student filed under it — into this one.
            School obj;
            if (dto.getId() != null) {
                obj = schoolRepository.findByIdScoped(dto.getId(), orgId, userId).orElse(null);
                if (obj == null) return new GenericResponse("NOT_FOUND", "School not found");
            } else {
                obj = new School();
            }
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

            // Scoped too: an unchecked findById here let a caller attach ANOTHER tenant's owner to their
            // own branch, leaking that owner's name/contact back through every school read.
            Set<Owner> owners = new HashSet<>();
            if (dto.getOwnerIds() != null) {
                for (Long id : dto.getOwnerIds()) {
                    if (!appUtil.isEmptyOrNull(id)) {
                        ownerRepository.findByIdScoped(id, orgId, userId).ifPresent(owners::add);
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

    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @RequestMapping(value = "/deleteSchool", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public boolean deleteSchool(HttpServletRequest req) {
        String ids = req.getParameter("checked");
        if (StringUtils.isEmpty(ids)) return false;
        // No internal catch: propagate so @Transactional rolls back the whole multi-row delete.
        // Anti-IDOR: the caller's own tenant only — and, since a School IS the branch, only a branch they
        // hold (School::getId is its own location id), so a teacher cannot delete another campus.
        scopedDeleter.deleteScoped(schoolRepository, ids,
                School::getOrganizationId, School::getUserId, School::getId);
        return true;
    }

    /**
     * Turns an uncaught exception from a transactional write (deleteSchool) back into the
     * GenericResponse("ERROR", …) envelope; the @Transactional method has already rolled back.
     */
    // A @PreAuthorize denial throws AccessDeniedException; this controller's broad Exception handler below
    // would otherwise swallow it into a 200 "ERROR" envelope. A more-specific handler wins → clean 403.
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    @ResponseBody
    public org.springframework.http.ResponseEntity<GenericResponse> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException e) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                .body(new GenericResponse("FORBIDDEN", "Access denied"));
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public GenericResponse handleUncaught(Exception e) {
        appUtil.le(getClass(), e);
        return new GenericResponse("ERROR", e.getMessage());
    }
}
