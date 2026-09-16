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

    /**
     * P5 — the refresh token of the session ASKING for the change, so that session survives it.
     *
     * <p>Optional, and deliberately NOT {@code @NotBlank}: a client older than this change sends no token and must
     * keep working, which means revoking everything — the behaviour before P5. The same shape as
     * {@code LogoutRequest}, for the same reason: the JWT carries no session id, so the refresh token is the only
     * thing that can name WHICH device is asking.
     */
    private String refreshToken;
}
