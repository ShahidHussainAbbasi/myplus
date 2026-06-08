package com.myplus.appointment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PatientDTO {
    private Long id;
    @NotBlank
    private String name;
    private String phone;
    private String email;
}
