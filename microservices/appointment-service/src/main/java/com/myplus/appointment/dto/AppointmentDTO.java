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
}
