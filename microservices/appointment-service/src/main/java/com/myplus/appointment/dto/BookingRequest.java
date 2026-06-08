package com.myplus.appointment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Anonymous public patient booking against a specific hospital (org inferred from the hospital). */
@Data
public class BookingRequest {
    @NotNull
    private Long hospitalId;
    private Long doctorId;
    @NotBlank
    private String patientName;
    private String patientPhone;
    private String patientEmail;
    private String appointmentType;
    private String dateTime;
    private String date;
}
