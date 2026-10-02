package com.myplus.business_service.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** FP-2 — the run-once marker for a tenant's automatic payables backfill (V71). */
@Entity
@Table(name = "payable_backfill")
@Getter @Setter
public class PayableBackfill {

    @Id
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "documents", nullable = false)
    private Integer documents = 0;

    @Column(name = "done_at")
    private LocalDateTime doneAt;
}
