package com.myplus.pharma.dto;

import lombok.Data;

/** Per-item clinical flags I/O (P7, slice 44). */
@Data
public class ClinicalDTO {
    private Long productId;   // M5 (slice 100): catalog Product id
    private String medicineName;
    private boolean rxRequired;
    private boolean controlledSubstance;
    private String drugCategory;
}
