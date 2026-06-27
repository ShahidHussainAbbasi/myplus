package com.myplus.pharma.dto;

import lombok.Data;

/** Per-item clinical flags I/O (P7, slice 44). */
@Data
public class ClinicalDTO {
    private Long itemId;
    private String medicineName;
    private boolean rxRequired;
    private boolean controlledSubstance;
    private String drugCategory;
}
