package com.myplus.agriculture.controller;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.agriculture.dto.LandDTO;
import com.myplus.agriculture.entity.Land;
import com.myplus.agriculture.service.ILandService;
import com.myplus.agriculture.util.AppUtil;
import com.myplus.agriculture.util.GenericResponse;
import com.myplus.agriculture.util.RequestUtil;

@Controller
public class LandController {

    @Autowired
    ILandService service;
    @Autowired
    RequestUtil requestUtil;
    @Autowired
    AppUtil appUtil;

    private final ModelMapper modelMapper = new ModelMapper();

    @RequestMapping(value = "/addLand", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addLand(final LandDTO dto, final HttpServletRequest request) {
        try {
            Land obj = new Land();
            dto.setUserId(requestUtil.getCurrentUser().getUserId());
            if (appUtil.isEmptyOrNull(dto.getId())) {
                obj.setUserId(dto.getUserId());
                obj.setLandName(dto.getLandName());
                Example<Land> example = Example.of(obj);
                if (service.exists(example)) {
                    return new GenericResponse(appUtil.FOUND, "The Land " + dto.getLandName() + " already exists");
                }
            }
            obj = modelMapper.map(dto, Land.class);
            obj.setDated(LocalDate.now());
            obj.setUpdated(appUtil.getLocalDate(dto.getUpdatedStr()));
            if (service.save(obj).getId() > 0) {
                return new GenericResponse(appUtil.SUCCESS, "Land added successfully");
            }
            return new GenericResponse(appUtil.FAILED, "Sorry, your land was not added");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse(appUtil.NOT_FOUND, "System error", dto);
        }
    }

    @RequestMapping(value = "/getUserLand", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserLand(final HttpServletRequest request) {
        try {
            List<LandDTO> dtos = new ArrayList<>();
            Land filterBy = new Land();
            filterBy.setUserId(requestUtil.getCurrentUser().getUserId());
            Example<Land> example = Example.of(filterBy);
            List<Land> objs = service.findAll(example);
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse(appUtil.NOT_FOUND, "No data found", objs);
            }
            for (Land obj : objs) {
                LandDTO dto = modelMapper.map(obj, LandDTO.class);
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

    @RequestMapping(value = "/getUserLands", method = RequestMethod.GET)
    @ResponseBody
    public String getUserLands(final HttpServletRequest request) {
        StringBuffer sb = new StringBuffer();
        try {
            Land filterBy = new Land();
            filterBy.setUserId(requestUtil.getCurrentUser().getUserId());
            Example<Land> example = Example.of(filterBy);
            List<Land> objs = service.findAll(example);
            if (appUtil.isEmptyOrNull(objs)) {
                sb.append("<option class='dropdown-item' value=''> No Data </option>");
            } else {
                sb.append("<option class='dropdown-item' value=''> Nothing Selected </option>");
            }
            objs.forEach(d -> {
                if (d != null && d.getId() != null) {
                    sb.append("<option class='dropdown-item' value=" + d.getId() + ">"
                            + d.getLandName() + "- (" + d.getTotalLandUnit() + " " + d.getLandUnit() + ")" + "</option>");
                }
            });
        } catch (Exception e) {
            appUtil.le(getClass(), e);
        }
        return sb.toString();
    }

    @RequestMapping(value = "/getAllLand", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllLand(final HttpServletRequest request) {
        try {
            List<Land> objs = service.findAll();
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse(appUtil.NOT_FOUND);
            }
            return new GenericResponse(appUtil.SUCCESS, objs);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse(appUtil.ERROR, e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteLand", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse deleteLand(HttpServletRequest request) {
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
