package com.myplus.education.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.dto.GuardianDTO;
import com.myplus.education.entity.Guardian;
import com.myplus.education.repository.GuardianRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/** Flat (legacy) Guardian endpoints. userId-scoped. */
@Controller
public class GuardianController {

    @Autowired
    private GuardianRepository guardianRepository;
    @Autowired
    private RequestUtil requestUtil;
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

    private GuardianDTO toDto(Guardian g) {
        GuardianDTO dto = new GuardianDTO();
        dto.setId(g.getId());
        dto.setUserId(g.getUserId());
        dto.setName(g.getName());
        dto.setEmail(g.getEmail());
        dto.setMobile(g.getMobile());
        dto.setPhone(g.getPhone());
        dto.setTempAddress(g.getTempAddress());
        dto.setPermAddress(g.getPermAddress());
        dto.setGender(g.getGender());
        dto.setRelation(g.getRelation());
        dto.setOccupation(g.getOccupation());
        dto.setStatus(g.getStatus());
        dto.setCnic(g.getCnic());
        dto.setDatedStr(appUtil.getDateStr(g.getDated()));
        dto.setUpdatedStr(appUtil.getDateStr(g.getUpdated()));
        return dto;
    }

    @RequestMapping(value = "/getUserGuardian", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserGuardian(final HttpServletRequest request) {
        try {
            List<Guardian> objs = guardianRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getUserGuardians", method = RequestMethod.GET)
    @ResponseBody
    public String getUserGuardians(final HttpServletRequest request) {
        StringBuffer sb = new StringBuffer();
        try {
            List<Guardian> objs = guardianRepository.findScoped(orgId(), userId());
            sb.append("<option value=''>Nothing Selected</option>");
            objs.forEach(d -> {
                if (d != null && d.getId() != null) {
                    sb.append("<option value=" + d.getId() + ">" + d.getName() + "</option>");
                }
            });
        } catch (Exception e) {
            appUtil.le(getClass(), e);
        }
        return sb.toString();
    }

    @RequestMapping(value = "/getAllGuardian", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllGuardian(final HttpServletRequest request) {
        try {
            // Tenant-scoped: "all" means all guardians in the active organization, not every tenant's.
            List<Guardian> all = guardianRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addGuardian", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addGuardian(final GuardianDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();
            if (appUtil.isEmptyOrNull(dto.getId())) {
                boolean exists = guardianRepository.findScoped(orgId, userId).stream()
                        .anyMatch(g -> g.getName() != null && g.getName().equalsIgnoreCase(dto.getName())
                                && g.getCnic() != null && g.getCnic().equalsIgnoreCase(dto.getCnic()));
                if (exists) {
                    return new GenericResponse("FOUND", "The Guardian '" + dto.getName() + "' already exists");
                }
            }
            Guardian obj = (dto.getId() != null)
                    ? guardianRepository.findById(dto.getId()).orElseGet(Guardian::new)
                    : new Guardian();
            obj.setUserId(userId);              // audit: who created/edited
            obj.setOrganizationId(orgId);       // tenant scope
            obj.setName(dto.getName());
            obj.setEmail(dto.getEmail());
            obj.setMobile(dto.getMobile());
            obj.setPhone(dto.getPhone());
            obj.setTempAddress(dto.getTempAddress());
            obj.setPermAddress(dto.getPermAddress());
            obj.setGender(dto.getGender());
            obj.setRelation(dto.getRelation());
            obj.setOccupation(dto.getOccupation());
            obj.setStatus(dto.getStatus());
            obj.setCnic(dto.getCnic());
            if (obj.getDated() == null) {
                obj.setDated(LocalDateTime.now());
            }
            obj.setUpdated(LocalDateTime.now());
            Guardian saved = guardianRepository.save(obj);
            return appUtil.isEmptyOrNull(saved)
                    ? new GenericResponse("FAILED", "")
                    : new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteGuardian", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse deleteGuardian(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (StringUtils.isEmpty(ids)) {
                return new GenericResponse(appUtil.SUCCESS, "Invalid input");
            }
            for (String id : ids.split(",")) {
                if (!StringUtils.isEmpty(id)) {
                    guardianRepository.deleteById(Long.valueOf(id));
                }
            }
            return new GenericResponse(appUtil.SUCCESS, "Deleted successfully");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse(appUtil.ERROR, e.getMessage());
        }
    }
}
