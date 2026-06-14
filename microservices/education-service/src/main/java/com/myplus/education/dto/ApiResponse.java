package com.myplus.education.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
