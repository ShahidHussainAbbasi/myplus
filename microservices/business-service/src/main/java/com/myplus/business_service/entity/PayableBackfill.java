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

    /** FP-4a — the snapshot generation this tenant was replayed with (1 = FP-2, 2 = with the statement trail). */
    @Column(name = "generation", nullable = false)
    private Integer generation = 1;

    @Column(name = "done_at")
    private LocalDateTime doneAt;
}
