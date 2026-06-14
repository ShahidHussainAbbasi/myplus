package com.myplus.education.controller;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.repository.GuardianRepository;
import com.myplus.education.repository.SchoolRepository;
import com.myplus.education.repository.StaffRepository;
import com.myplus.education.repository.StudentRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/** Flat (legacy) education dashboard stats consumed by educationDashboard.js (data.object). */
@Controller
public class DashboardController {

    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private SchoolRepository schoolRepository;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private GuardianRepository guardianRepository;
    @Autowired
    private RequestUtil requestUtil;
    @Autowired
    private AppUtil appUtil;

    @RequestMapping(value = "/getDashboardData", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getDashboardData(final HttpServletRequest request) {
        try {
            AuthenticatedUser user = requestUtil.getCurrentUser();
            Long userId = user.getUserId();

            long allStudent = studentRepository.countByUserId(userId);
            int currentYear = LocalDate.now().getYear();
            long freshStudent = studentRepository.findByUserId(userId).stream()
                    .filter(s -> s.getEnrollDate() != null && s.getEnrollDate().getYear() == currentYear)
                    .count();

            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("allStudent", allStudent);
            obj.put("freshStudent", freshStudent);
            obj.put("totalSchools", schoolRepository.countByUserId(userId));
            obj.put("totalStaff", staffRepository.countByUserId(userId));
            obj.put("totalGuardians", guardianRepository.countByUserId(userId));
            return new GenericResponse("SUCCESS", obj);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }
}
