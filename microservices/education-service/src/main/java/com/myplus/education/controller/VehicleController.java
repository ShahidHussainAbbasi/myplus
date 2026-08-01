package com.myplus.education.controller;

import java.time.LocalDateTime;
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
import com.myplus.education.dto.VehicleDTO;
import com.myplus.education.entity.Vehicle;
import com.myplus.education.repository.SchoolRepository;
import com.myplus.education.repository.VehicleRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;
import com.myplus.education.util.ScopedDeleter;

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

    /** P4 role×branch visibility — see StudentController.visibleStudents() for the rule. */
    private List<Vehicle> visibleVehicles() {
        if (requestUtil.isOwnerSuper()) return vehicleRepository.findScoped(orgId(), userId());
        java.util.Set<Long> schools = requestUtil.accessibleSchoolIds();
        if (schools.isEmpty()) return vehicleRepository.findScoped(orgId(), userId());
        return vehicleRepository.findScopedBySchools(orgId(), schools);
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
        // Branch name resolved in batch by toDtos() — per-row findById here was an N+1 over the vehicle list.
        return dto;
    }

    /** Map the list, resolving each vehicle's branch name with ONE findAllById over the distinct school ids. */
    private List<VehicleDTO> toDtos(List<Vehicle> vehicles) {
        java.util.Set<Long> schoolIds = new java.util.HashSet<>();
        for (Vehicle v : vehicles) if (v.getSchoolId() != null) schoolIds.add(v.getSchoolId());
        java.util.Map<Long, String> schoolNames = new java.util.HashMap<>();
        if (!schoolIds.isEmpty())
            schoolRepository.findAllById(schoolIds).forEach(s -> schoolNames.put(s.getId(), s.getBranchName()));
        return vehicles.stream().map(v -> {
            VehicleDTO dto = toDto(v);
            if (v.getSchoolId() != null) dto.setSchoolName(schoolNames.get(v.getSchoolId()));
            return dto;
        }).collect(Collectors.toList());
    }

    @RequestMapping(value = "/getUserVehicle", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserVehicle(final HttpServletRequest request) {
        try {
            List<Vehicle> objs = visibleVehicles();
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", toDtos(objs));
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
            List<Vehicle> objs = visibleVehicles();
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
            // Tenant- AND branch-scoped: every vehicle the caller may see in the active org.
            List<Vehicle> all = visibleVehicles();
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", toDtos(all));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // D-3 privilege map: money / structure / policy — not routine data entry
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @RequestMapping(value = "/addVehicle", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addVehicle(final VehicleDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();
            if (appUtil.isEmptyOrNull(dto.getId())) {
                boolean exists = vehicleRepository.existsByNumberScoped(dto.getNumber(), orgId, userId);
                if (exists) {
                    return new GenericResponse("FOUND", "A vehicle '" + dto.getNumber() + "' already exists");
                }
            }
            Vehicle obj = (dto.getId() != null)
                    ? vehicleRepository.findById(dto.getId()).orElseGet(Vehicle::new)
                    : new Vehicle();
            // P4 anti-IDOR: an edit names a row by id, so it must live in a branch the caller may access.
            if (dto.getId() != null && obj.getId() != null && !requestUtil.canAccessSchool(obj.getSchoolId())) {
                return new GenericResponse("NOT_FOUND", "Vehicle not found");
            }
            Long school = dto.getSchoolId() != null ? dto.getSchoolId() : requestUtil.activeSchoolId();
            if (!requestUtil.canAccessSchool(school)) {
                return new GenericResponse("FAILED", "You do not have access to that branch.");
            }
            obj.setUserId(userId);              // audit: who created/edited
            obj.setOrganizationId(orgId);       // tenant scope
            obj.setName(dto.getName());
            obj.setNumber(dto.getNumber());
            obj.setDriverName(dto.getDriverName());
            obj.setDriverMobile(dto.getDriverMobile());
            obj.setOwnerName(dto.getOwnerName());
            obj.setOwnerMobile(dto.getOwnerMobile());
            obj.setStatus(dto.getStatus());
            obj.setSchoolId(school);
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

    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @RequestMapping(value = "/deleteVehicle", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteVehicle(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                // Anti-IDOR: the caller's own tenant and own branch only (see ScopedDeleter).
                scopedDeleter.deleteScoped(vehicleRepository, ids,
                        Vehicle::getOrganizationId, Vehicle::getUserId, Vehicle::getSchoolId);
                return true;
            }
            return false;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return false;
        }
    }
}
