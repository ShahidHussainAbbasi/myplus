package com.myplus.education.controller;

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
import com.myplus.education.dto.AttendanceDTO;
import com.myplus.education.entity.Attendance;
import com.myplus.education.repository.AttendanceRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Flat (legacy) Attendance endpoints — core list/delete. userId-scoped.
 * NOTE: markAttendance/markAttendance2 (bulk marking), findA, sendPA (parent alerts) and
 * getUserStudentMap are deferred to a focused follow-up (multi-student marking + alerting logic).
 */
@Controller
public class AttendanceController {

    @Autowired
    private AttendanceRepository attendanceRepository;
    @Autowired
    private RequestUtil requestUtil;
    @Autowired
    private AppUtil appUtil;

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    private AttendanceDTO toDto(Attendance a) {
        AttendanceDTO dto = new AttendanceDTO();
        dto.setId(a.getId());
        dto.setUserId(a.getUserId());
        dto.setEn(a.getEn());
        dto.setSn(a.getSn());
        dto.setGrid(a.getGrid());
        dto.setGn(a.getGn());
        dto.setStatus(a.getStatus());
        dto.setDt(a.getDt());
        dto.setIn(a.getIn());
        dto.setOut(a.getOut());
        dto.setRem(a.getRem());
        dto.setDtStr(appUtil.getLocalDateTimeStr(a.getDt()));
        return dto;
    }

    @RequestMapping(value = "/getUserA", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserA(final HttpServletRequest request) {
        try {
            List<Attendance> objs = attendanceRepository.findByUserId(userId());
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getAllA", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllA(final HttpServletRequest request) {
        try {
            List<Attendance> all = attendanceRepository.findAll();
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteA", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteA(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                for (String id : ids.split(",")) {
                    if (!StringUtils.isEmpty(id)) {
                        attendanceRepository.deleteById(Long.valueOf(id));
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
