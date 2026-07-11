package com.myplus.audit.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.*;

/** Ingestion payload from a producer. Identity (org + actor) is taken from the authenticated request, not this body. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AuditRecordRequest {
    private String sourceService;
    private String action;
    private String entityType;
    private String entityRef;
    private BigDecimal amount;
    private String details;
    private String eventKey;
    private LocalDateTime occurredAt;
}
