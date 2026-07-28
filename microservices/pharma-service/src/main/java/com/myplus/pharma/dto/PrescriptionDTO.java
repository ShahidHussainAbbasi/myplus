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
    private Long partyId;   // P3: shared party/contact master id
    private LocalDateTime createdAt;
    private List<PrescriptionItemDTO> items = new ArrayList<>();

    /**
     * Populated only on a dispense response: what the server silently adjusted (lines capped to the prescribed
     * quantity, sold items that are not on this prescription, a repeat post that was ignored). Empty otherwise.
     * The pharmacist has to see these — the sale already left the counter with the full quantity.
     */
    private List<String> warnings = new ArrayList<>();
}
