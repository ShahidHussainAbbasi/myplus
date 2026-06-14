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
import com.myplus.education.dto.VehicleDTO;
import com.myplus.education.entity.Vehicle;
import com.myplus.education.repository.SchoolRepository;
import com.myplus.education.repository.VehicleRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/** Flat (legacy) Vehicle (transport) endpoints. userId-scoped. */
@Controller
public class VehicleController {

    @Autowired
    private VehicleRepository vehicleRepository;
    @Autowired
    private SchoolRepository schoolRepository;
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

    private VehicleDTO toDto(Vehicle v) {
        VehicleDTO dto = new VehicleDTO();
        dto.setId(v.getId());
        dto.setUserId(v.getUserId());
        dto.setName(v.getName());
        dto.setNumber(v.getNumber());
        dto.setDriverName(v.getDriverName());
        dto.setDriverMobile(v.getDriverMobile());
        dto.setOwnerName(v.getOwnerName());
        dto.setOwnerMobile(v.getOwnerMobile());
        dto.setStatus(v.getStatus());
        dto.setSchoolId(v.getSchoolId());
        dto.setDatedStr(appUtil.getDateStr(v.getDated()));
        dto.setUpdatedStr(appUtil.getDateStr(v.getUpdated()));
        if (v.getSchoolId() != null) {
            schoolRepository.findById(v.getSchoolId()).ifPresent(s -> dto.setSchoolName(s.getBranchName()));
        }
        return dto;
    }

    @RequestMapping(value = "/getUserVehicle", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserVehicle(final HttpServletRequest request) {
        try {
            List<Vehicle> objs = vehicleRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getUserVehicles", method = RequestMethod.GET)
    @ResponseBody
    public String getUserVehicles(final HttpServletRequest request) {
        StringBuffer sb = new StringBuffer();
        try {
            List<Vehicle> objs = vehicleRepository.findScoped(orgId(), userId());
            sb.append("<option value=''>Nothing Selected</option>");
            objs.forEach(d -> {
                if (d != null && d.getId() != null) {
                    sb.append("<option value=" + d.getId() + ">" + d.getName() + " (" + d.getNumber() + ")</option>");
                }
            });
        } catch (Exception e) {
            appUtil.le(getClass(), e);
        }
        return sb.toString();
    }

    @RequestMapping(value = "/getAllVehicle", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllVehicle(final HttpServletRequest request) {
        try {
            // Tenant-scoped: "all" means all vehicles in the active organization, not every tenant's.
            List<Vehicle> all = vehicleRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addVehicle", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addVehicle(final VehicleDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();
            if (appUtil.isEmptyOrNull(dto.getId())) {
                boolean exists = vehicleRepository.findScoped(orgId, userId).stream()
                        .anyMatch(v -> v.getNumber() != null && v.getNumber().equalsIgnoreCase(dto.getNumber()));
                if (exists) {
                    return new GenericResponse("FOUND", "A vehicle '" + dto.getNumber() + "' already exists");
                }
            }
            Vehicle obj = (dto.getId() != null)
                    ? vehicleRepository.findById(dto.getId()).orElseGet(Vehicle::new)
                    : new Vehicle();
            obj.setUserId(userId);              // audit: who created/edited
            obj.setOrganizationId(orgId);       // tenant scope
            obj.setName(dto.getName());
            obj.setNumber(dto.getNumber());
            obj.setDriverName(dto.getDriverName());
            obj.setDriverMobile(dto.getDriverMobile());
            obj.setOwnerName(dto.getOwnerName());
            obj.setOwnerMobile(dto.getOwnerMobile());
            obj.setStatus(dto.getStatus());
            obj.setSchoolId(dto.getSchoolId());
            if (obj.getDated() == null) {
                obj.setDated(LocalDateTime.now());
            }
            obj.setUpdated(LocalDateTime.now());
            Vehicle saved = vehicleRepository.save(obj);
            return appUtil.isEmptyOrNull(saved)
                    ? new GenericResponse("FAILED", "")
                    : new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteVehicle", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteVehicle(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                for (String id : ids.split(",")) {
                    if (!StringUtils.isEmpty(id)) {
                        vehicleRepository.deleteById(Long.valueOf(id));
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
