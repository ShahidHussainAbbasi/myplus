package com.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The standard auth-service response envelope ({@code com.myplus.auth.dto.ApiResponse}) wrapping
 * a login/refresh payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthServerEnvelope {

    private boolean success;
    private String message;
    private AuthServerLoginResponse data;

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public AuthServerLoginResponse getData() { return data; }
    public void setData(AuthServerLoginResponse data) { this.data = data; }
}
