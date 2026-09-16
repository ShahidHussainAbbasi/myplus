package com.myplus.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AUTH-SESS-1 — what a logout may say about WHICH session is ending.
 *
 * <p>⚠ Deliberately NOT {@code @NotBlank}, unlike {@link RefreshTokenRequest}. The whole body is optional: a client
 * that sends its refresh token ends that one device, and a client that sends nothing gets the old revoke-everything
 * behaviour. Validating the field would turn the compatibility path into a 400 for every caller written before this
 * slice.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogoutRequest {
    private String refreshToken;
}
