package com.myplus.appointment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class HospitalDTO {
    private Long id;
    @NotBlank
    private String name;
    private String email;
    private String phone;
    private String logoUrl;
    private String country;
    private String state;
    private String city;
}
