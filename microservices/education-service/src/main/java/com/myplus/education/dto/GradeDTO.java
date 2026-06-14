package com.myplus.education.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GradeDTO {
    private Long id;
    private Long userId;
    private String name;
    private String code;
    private String section;
    private Long schoolId;
    private String schoolName;
    private String timeFromStr;
    private String timeToStr;
    private String status;
    private Float fee;
    private Long room;
    private String datedStr;
    private String updatedStr;
}
