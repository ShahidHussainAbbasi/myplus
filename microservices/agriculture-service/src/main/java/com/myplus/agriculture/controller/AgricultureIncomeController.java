package com.myplus.agriculture.controller;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.agriculture.dto.AgricultureIncomeDTO;
import com.myplus.agriculture.entity.AgricultureIncome;
import com.myplus.agriculture.entity.Land;
import com.myplus.agriculture.service.IAgricultureIncomeService;
import com.myplus.agriculture.service.ILandService;
import com.myplus.agriculture.util.AppUtil;
import com.myplus.agriculture.util.GenericResponse;
import com.myplus.agriculture.util.RequestUtil;

@Controller
public class AgricultureIncomeController {

    @Autowired
    IAgricultureIncomeService service;
    @Autowired
    ILandService landService;
    @Autowired
    RequestUtil requestUtil;
    @Autowired
    AppUtil appUtil;

    private final ModelMapper modelMapper = new ModelMapper();

    @RequestMapping(value = "/addAgricultureIncome", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addAgricultureIncome(final AgricultureIncomeDTO dto, final HttpServletRequest request) {
        try {
            Long userId = requestUtil.getCurrentUser().getUserId();
            AgricultureIncome obj;
            if (appUtil.isEmptyOrNull(dto.getId())) {
                obj = new AgricultureIncome(userId, dto.getLandId(), dto.getIncomeName(), appUtil.getLocalDate(dto.getUpdatedStr()));
                Example<AgricultureIncome> example = Example.of(obj);
                if (service.exists(example)) {
                    return new GenericResponse(appUtil.INVALID, "The Income " + dto.getIncomeName() + " exists or is invalid");
                }
            }
            obj = modelMapper.map(dto, AgricultureIncome.class);
            obj.setUserId(userId);
            obj.setDated(LocalDate.now());
            obj.setUpdated(appUtil.getLocalDate(dto.getUpdatedStr()));
            Optional<Land> optional = landService.findById(dto.getLandId());
            if (optional.isPresent()) {
                Land land = optional.get();
                obj.setLandId(land.getId());
                obj.setLandName(land.getLandName());
            }
            if (service.save(obj).getId() > 0) {
                return new GenericResponse(appUtil.SUCCESS, "Income added successfully");
            }
            return new GenericResponse(appUtil.FAILED, "Sorry, your income was not submitted");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse(appUtil.NOT_FOUND, "Sorry, your income was not submitted", dto);
        }
    }

    @RequestMapping(value = "/getUserAgricultureIncome", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserAgricultureIncome(final HttpServletRequest request) {
        try {
            List<AgricultureIncomeDTO> dtos = new ArrayList<>();
            AgricultureIncome filterBy = new AgricultureIncome(requestUtil.getCurrentUser().getUserId());
            Example<AgricultureIncome> example = Example.of(filterBy);
            List<AgricultureIncome> objs = service.findAll(example);
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse(appUtil.NOT_FOUND, "No data found", objs);
            }
            for (AgricultureIncome obj : objs) {
                AgricultureIncomeDTO dto = modelMapper.map(obj, AgricultureIncomeDTO.class);
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

    @RequestMapping(value = "/income/loadLastCropAttached", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse loadLastIncomeCropAttached(@RequestParam Long landId, final HttpServletRequest request) {
        try {
            AgricultureIncome filterBy = new AgricultureIncome(requestUtil.getCurrentUser().getUserId());
            filterBy.setLandId(landId);
            Example<AgricultureIncome> example = Example.of(filterBy);
            List<AgricultureIncome> objs = service.findAll(example);
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse(appUtil.NOT_FOUND, "No data found");
            }
            objs.sort(Comparator.comparing(AgricultureIncome::getUpdated).reversed());
            AgricultureIncomeDTO dto = modelMapper.map(objs.get(0), AgricultureIncomeDTO.class);
            return new GenericResponse(appUtil.SUCCESS, dto);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse(appUtil.ERROR, e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteAgricultureIncome", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse deleteAgricultureIncome(HttpServletRequest request) {
        try {
            String ids = request.getParameter("checked");
            if (StringUtils.isEmpty(ids)) {
                return new GenericResponse(appUtil.SUCCESS, "Invalid input");
            }
            for (String id : ids.split(",")) {
                service.deleteById(Long.valueOf(id));
            }
            return new GenericResponse(appUtil.SUCCESS, "Deleted successfully");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse(appUtil.ERROR, e.getMessage());
        }
    }
}
