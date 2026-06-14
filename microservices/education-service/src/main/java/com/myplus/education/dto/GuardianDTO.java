package com.myplus.education.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GuardianDTO {
    private Long id;
    private Long userId;
    private String name;
    private String email;
    private String mobile;
    private String phone;
    private String tempAddress;
    private String permAddress;
    private String gender;
    private String relation;
    private String occupation;
    private String status;
    private String cnic;
    private String datedStr;
    private String updatedStr;
}
