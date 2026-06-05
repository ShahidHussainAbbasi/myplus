package com.myplus.education.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StudentDTO {
    private Long id;
    private Long userId;
    private String name;
    private String enrollNo;
    private String enrollDateStr;
    private String ysStr;
    private String yeStr;
    private String feeMode;
    private String email;
    private String mobile;
    private String address;
    private String dateOfBirthStr;
    private String gender;
    private String bloodGroup;
    private String status;
    private Long schoolId;
    private String schoolName;
    private Long guardianId;
    private String guardianName;
    private Long gradeId;
    private String gradeName;
    private Long vehicleId;
    private Long discountId;
    private Integer nd;
    private String datedStr;
    private String updatedStr;
}
