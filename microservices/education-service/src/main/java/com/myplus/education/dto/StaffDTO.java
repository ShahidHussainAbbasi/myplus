package com.myplus.education.dto;

import java.util.Set;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StaffDTO {
    private Long id;
    private Long userId;
    private String name;
    private String email;
    private String mobile;
    private String phone;
    private String address;
    private String designation;
    private String staffDOBStr;
    private String gender;
    private String timeInStr;
    private String timeOutStr;
    private String qualification;
    private String martialStatus;
    private String status;
    private Set<Long> gradeIds;
    private Set<String> gradeNames;
    private String datedStr;
    private String updatedStr;
}
