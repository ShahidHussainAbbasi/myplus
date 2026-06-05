package com.myplus.education.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SubjectDTO {
    private Long id;
    private Long userId;
    private String name;
    private String code;
    private String publisher;
    private String edition;
    private Long gradeId;
    private String gradeName;
    private String status;
    private String datedStr;
    private String updatedStr;
}
