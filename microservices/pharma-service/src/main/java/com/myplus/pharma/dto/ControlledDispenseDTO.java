package com.myplus.pharma.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** One row of the controlled-substance register (P8, slice 45). */
@Data
public class ControlledDispenseDTO {
    private LocalDateTime dispensedAt;
    private Long productId;   // M5 (slice 100): catalog Product id
    private String medicineName;
    private int quantity;
    private String patientName;
    private String invoiceNo;
    private Long dispensedBy;
}
