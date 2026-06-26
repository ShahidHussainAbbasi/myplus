package com.myplus.pharma.dto;

import lombok.Data;

/** One prescribed line (P5, slice 41) — the catalog product (medicine) + dosing instructions. */
@Data
public class PrescriptionItemDTO {
    private Long id;
    private Long itemId;           // the business Item (medicine) — same id the sell flow uses
    private String medicineName;   // snapshot for display
    private int quantity;
    private String dosage;
    private String frequency;
    private String duration;
    private Integer dispensedQuantity;
}
