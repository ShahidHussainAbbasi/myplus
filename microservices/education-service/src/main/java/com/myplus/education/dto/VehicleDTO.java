package com.myplus.education.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class VehicleDTO {
    private Long id;
    private Long userId;
    private String name;
    private String number;
    private String driverName;
    private String driverMobile;
    private String ownerName;
    private String ownerMobile;
    private String status;
    private Long schoolId;
    private String schoolName;
    private String datedStr;
    private String updatedStr;
}
