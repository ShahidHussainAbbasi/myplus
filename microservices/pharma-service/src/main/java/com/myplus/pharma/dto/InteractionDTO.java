package com.myplus.pharma.dto;

import lombok.Data;

/** Drug-interaction I/O (P7, slice 44). */
@Data
public class InteractionDTO {
    private Long productId1;   // M5 (slice 100): catalog Product ids
    private Long productId2;
    private String severity;        // MILD | MODERATE | SEVERE
    private String description;
    private String recommendation;
}
