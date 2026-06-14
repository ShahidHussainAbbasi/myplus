package com.myplus.appointment.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AppointmentDTO {
    private Long id;
    @NotNull
    private Long hospitalId;
    private Long doctorId;
    private Long patientId;
    private String appointmentType;
    private String fee;
    private String dateTime;
    private String date;
    private Integer patientsToVisit;
    private Integer patientsAppointed;
    private Integer patientsVisited;
    // Resolved display names so the dashboard/booking don't need extra lookups.
    private String patientName;
    private String patientPhone;
    private String doctorName;
    private String hospitalName;
}
