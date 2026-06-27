package com.myplus.common.notify;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A request to send a plain-text email (slice 33, Phase 8). {@code to} is required; {@code cc} optional
 * (e.g. the admin copy education's alerts include); {@code replyTo} optional (e.g. the demo-lead's address
 * so the team can reply straight to the requester).
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class EmailRequest {
    private List<String> to;
    private List<String> cc;
    private String replyTo;
    private String subject;
    private String body;
}
