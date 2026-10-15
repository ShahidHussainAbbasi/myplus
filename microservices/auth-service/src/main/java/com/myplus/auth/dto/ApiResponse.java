package com.myplus.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;
    private int statusCode;
    /**
     * A refusal's STABLE code (e.g. {@code ACCESS_REMOVED}) for clients to branch on — never on {@code message},
     * which is English prose for people and logs. Null on success and on refusals that carry no code yet.
     */
    private String reason;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "Success", data, 200, null);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, message, data, 200, null);
    }

    public static <T> ApiResponse<T> error(String message, int code) {
        return new ApiResponse<>(false, message, null, code, null);
    }

    /** A refusal carrying its stable code — see {@link #reason}. */
    public static <T> ApiResponse<T> refused(String reason, String message, int code) {
        return new ApiResponse<>(false, message, null, code, reason);
    }
}
