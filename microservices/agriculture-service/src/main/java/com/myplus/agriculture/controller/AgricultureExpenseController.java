package com.myplus.agriculture.controller;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.agriculture.dto.AgricultureExpenseDTO;
import com.myplus.agriculture.entity.AgricultureExpense;
import com.myplus.agriculture.entity.Land;
import com.myplus.agriculture.service.IAgricultureExpenseService;
import com.myplus.agriculture.service.ILandService;
import com.myplus.agriculture.util.AppUtil;
import com.myplus.agriculture.util.GenericResponse;
import com.myplus.agriculture.util.RequestUtil;

@Controller
public class AgricultureExpenseController {

    @Autowired
    IAgricultureExpenseService service;
    @Autowired
    ILandService landService;
    @Autowired
    RequestUtil requestUtil;
    @Autowired
    AppUtil appUtil;

    private final ModelMapper modelMapper = new ModelMapper();

    private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getUserId(); }
    /** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
    private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getOrganizationId(); }
    private boolean inMyTenant(Long rowOrg, Long rowUser) {
        return (rowOrg != null && rowOrg.equals(orgId()))
            || (rowOrg == null && rowUser != null && rowUser.equals(userId()));
    }

    @RequestMapping(value = "/addAgricultureExpense", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addAgricultureExpense(final AgricultureExpenseDTO dto, final HttpServletRequest request) {
        try {
            Long userId = requestUtil.getCurrentUser().getUserId();
            AgricultureExpense obj;
            if (appUtil.isEmptyOrNull(dto.getId())) {
                obj = new AgricultureExpense(userId, dto.getLandId(), dto.getExpenseName(), appUtil.getLocalDate(dto.getUpdatedStr()));
                Example<AgricultureExpense> example = Example.of(obj);
                if (service.exists(example)) {
                    return new GenericResponse(appUtil.INVALID, "The Expense " + dto.getExpenseName() + " exists or is invalid");
                }
            }
            obj = modelMapper.map(dto, AgricultureExpense.class);
            obj.setUserId(userId);                 // audit
            obj.setOrganizationId(orgId());        // tenant scope
            obj.setDated(LocalDate.now());
            obj.setUpdated(appUtil.getLocalDate(dto.getUpdatedStr()));
            Optional<Land> optional = landService.findById(dto.getLandId());
            if (optional.isPresent()) {
                Land land = optional.get();
                obj.setLandId(land.getId());
                obj.setLandName(land.getLandName());
            }
            if (service.save(obj).getId() > 0) {
                return new GenericResponse(appUtil.SUCCESS, "Expense added successfully");
            }
            return new GenericResponse(appUtil.FAILED, "Sorry, your expense was not submitted");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse(appUtil.NOT_FOUND, "Sorry, your expense was not submitted", dto);
        }
    }

    @RequestMapping(value = "/getUserAgricultureExpense", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserAgricultureExpense(final HttpServletRequest request) {
        try {
            List<AgricultureExpenseDTO> dtos = new ArrayList<>();
            List<AgricultureExpense> objs = service.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse(appUtil.NOT_FOUND, "No data found", objs);
            }
            for (AgricultureExpense obj : objs) {
                AgricultureExpenseDTO dto = modelMapper.map(obj, AgricultureExpenseDTO.class);
                dto.setDatedStr(appUtil.getLocalDateStr(obj.getDated()));
                dto.setUpdatedStr(appUtil.getLocalDateStr(obj.getUpdated()));
                dtos.add(dto);
            }
            return new GenericResponse(appUtil.SUCCESS, "", dtos);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse(appUtil.ERROR, e.getMessage());
        }
    }

    @RequestMapping(value = "/expense/loadLastCropAttached", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse loadLastExpenseCropAttached(@RequestParam Long landId, final HttpServletRequest request) {
        try {
            List<AgricultureExpense> objs = service.findScoped(orgId(), userId()).stream()
                    .filter(o -> java.util.Objects.equals(o.getLandId(), landId))
                    .sorted(java.util.Comparator.comparing(AgricultureExpense::getId).reversed())
                    .collect(java.util.stream.Collectors.toList());
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse(appUtil.NOT_FOUND, "No data found");
            }
            AgricultureExpenseDTO dto = modelMapper.map(objs.get(0), AgricultureExpenseDTO.class);
            return new GenericResponse(appUtil.SUCCESS, dto);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse(appUtil.ERROR, e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteAgricultureExpense", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse deleteAgricultureExpense(HttpServletRequest request) {
        try {
            String ids = request.getParameter("checked");
            if (StringUtils.isEmpty(ids)) {
                return new GenericResponse(appUtil.SUCCESS, "Invalid input");
            }
            java.util.Set<Long> owned = service.findScoped(orgId(), userId()).stream()
                    .map(AgricultureExpense::getId).collect(java.util.stream.Collectors.toSet());
            for (String id : ids.split(",")) {
                if (!StringUtils.isEmpty(id)) {
                    Long iid = Long.valueOf(id);
                    if (owned.contains(iid)) service.deleteById(iid);   // anti-IDOR
                }
            }
            return new GenericResponse(appUtil.SUCCESS, "Deleted successfully");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse(appUtil.ERROR, e.getMessage());
        }
    }
}
