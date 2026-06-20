package com.myplus.common.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard HTTP response envelope shared across services. Previously duplicated byte-for-byte in
 * inventory/pharma/analytics/marketplace (slice 33, Phase 1).
 */
@Data @AllArgsConstructor @NoArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;
    private int statusCode;

    public static <T> ApiResponse<T> success(T data) { return new ApiResponse<>(true, "Success", data, 200); }
    public static <T> ApiResponse<T> success(T data, String message) { return new ApiResponse<>(true, message, data, 200); }
    public static <T> ApiResponse<T> error(String message, int code) { return new ApiResponse<>(false, message, null, code); }
}
