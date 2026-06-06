package com.myplus.education.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DiscountDTO {
    private Long id;
    private Long userId;
    private String name;
    private String di; // percent / amount
    private Integer amount;
    private String startDateStr;
    private String endDateStr;
    private String description;
    private String referenceName;
    private String referenceMobile;
    private String status;
}
