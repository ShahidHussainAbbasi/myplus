package com.myplus.education.controller;

import com.myplus.education.dto.ApiResponse;
import com.myplus.education.dto.EducationDTOs.DashboardStatsDTO;
import com.myplus.education.repository.GuardianRepository;
import com.myplus.education.repository.SchoolRepository;
import com.myplus.education.repository.StaffRepository;
import com.myplus.education.repository.StudentRepository;
import com.myplus.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/education/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final SchoolRepository schoolRepository;
    private final StudentRepository studentRepository;
    private final StaffRepository staffRepository;
    private final GuardianRepository guardianRepository;

    @GetMapping("/stats")
    public ApiResponse<DashboardStatsDTO> stats(@AuthenticationPrincipal AuthenticatedUser user) {
        Long userId = user.getUserId();
        DashboardStatsDTO dto = DashboardStatsDTO.builder()
                .totalSchools(schoolRepository.countByUserId(userId))
                .totalStudents(studentRepository.countByUserId(userId))
                .totalStaff(staffRepository.countByUserId(userId))
                .totalGuardians(guardianRepository.countByUserId(userId))
                .build();
        return ApiResponse.success(dto);
    }
}
