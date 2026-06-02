package com.myplus.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {
    @NotBlank
    private String currentPassword;
    @NotBlank
    @Size(min = 8, max = 100)
    private String newPassword;
}
