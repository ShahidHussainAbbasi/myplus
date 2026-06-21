package com.myplus.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A request to send a plain-text email (slice 33, Phase 8). {@code to} is required; {@code cc} optional
 * (e.g. the admin copy education's alerts always include).
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class EmailRequest {
    private List<String> to;
    private List<String> cc;
    private String subject;
    private String body;
}
