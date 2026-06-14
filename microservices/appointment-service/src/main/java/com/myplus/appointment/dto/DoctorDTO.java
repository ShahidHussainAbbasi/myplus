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
    private String email;
    private String mobile;
    private String address;
    private String availabe;
    private String dayFrom;
    private String dayTo;
    private String timeIn;
    private String timeOut;
    private String appointmentOfferType;
    private Integer appointmentOfferValue;
}
