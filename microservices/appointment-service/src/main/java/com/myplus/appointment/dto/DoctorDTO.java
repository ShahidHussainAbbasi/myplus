package com.myplus.appointment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DoctorDTO {
    private Long id;
    @NotNull
    private Long hospitalId;
    @NotBlank
    private String name;
    private String speciality;
    private String fee;
}
