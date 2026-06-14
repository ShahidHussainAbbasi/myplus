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
import com.myplus.education.dto.OwnerDTO;
import com.myplus.education.entity.Owner;
import com.myplus.education.repository.OwnerRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/** Flat (legacy) Owner endpoints for the monolith education pages. userId-scoped. */
@Controller
public class OwnerController {

    @Autowired
    private OwnerRepository ownerRepository;
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

    private OwnerDTO toDto(Owner o) {
        OwnerDTO dto = new OwnerDTO();
        dto.setId(o.getId());
        dto.setUserId(o.getUserId());
        dto.setName(o.getName());
        dto.setEmail(o.getEmail());
        dto.setMobile(o.getMobile());
        dto.setAddress(o.getAddress());
        dto.setStatus(o.getStatus());
        dto.setDatedStr(appUtil.getDateStr(o.getDated()));
        dto.setUpdatedStr(appUtil.getDateStr(o.getUpdated()));
        return dto;
    }

    @RequestMapping(value = "/getUserOwner", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserOwner(final HttpServletRequest request) {
        try {
            List<Owner> objs = ownerRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getUserOwners", method = RequestMethod.GET)
    @ResponseBody
    public String getUserOwners(final HttpServletRequest request) {
        StringBuffer sb = new StringBuffer();
        try {
            List<Owner> objs = ownerRepository.findScoped(orgId(), userId());
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

    @RequestMapping(value = "/getAllOwner", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllOwner(final HttpServletRequest request) {
        try {
            // Tenant-scoped: "all" means all owners in the active organization, not every tenant's.
            List<Owner> all = ownerRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addOwner", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addOwner(final OwnerDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();
            if (appUtil.isEmptyOrNull(dto.getId())) {
                boolean exists = ownerRepository.findScoped(orgId, userId).stream()
                        .anyMatch(o -> o.getName() != null && o.getName().equalsIgnoreCase(dto.getName()));
                if (exists) {
                    return new GenericResponse("FOUND", "The Owner '" + dto.getName() + "' already exists");
                }
            }
            Owner obj = (dto.getId() != null)
                    ? ownerRepository.findById(dto.getId()).orElseGet(Owner::new)
                    : new Owner();
            obj.setUserId(userId);              // audit: who created/edited
            obj.setOrganizationId(orgId);       // tenant scope
            obj.setName(dto.getName());
            obj.setEmail(dto.getEmail());
            obj.setMobile(dto.getMobile());
            obj.setAddress(dto.getAddress());
            obj.setStatus(dto.getStatus());
            if (obj.getDated() == null) {
                obj.setDated(LocalDateTime.now());
            }
            obj.setUpdated(LocalDateTime.now());
            Owner saved = ownerRepository.save(obj);
            return appUtil.isEmptyOrNull(saved)
                    ? new GenericResponse("FAILED", "")
                    : new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteOwner", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteOwner(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                for (String id : ids.split(",")) {
                    if (!StringUtils.isEmpty(id)) {
                        ownerRepository.deleteById(Long.valueOf(id));
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
