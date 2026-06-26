package com.myplus.pharma.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** A prescription as the UI sees it (P5, slice 41): patient + prescriber + validity + the prescribed items. */
@Data
public class PrescriptionDTO {
    private Long id;
    private String patientName;
    private String patientPhone;
    private String doctorName;
    private String doctorLicense;
    private LocalDate prescribedDate;
    private LocalDate validUntil;
    private String diagnosis;
    private String notes;
    private String status;
    private LocalDateTime createdAt;
    private List<PrescriptionItemDTO> items = new ArrayList<>();
}
