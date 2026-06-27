package com.myplus.pharma.dto;

import lombok.Data;

/** Drug-interaction I/O (P7, slice 44). */
@Data
public class InteractionDTO {
    private Long itemId1;
    private Long itemId2;
    private String severity;        // MILD | MODERATE | SEVERE
    private String description;
    private String recommendation;
}
