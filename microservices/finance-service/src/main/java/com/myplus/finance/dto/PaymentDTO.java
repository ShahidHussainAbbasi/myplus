package com.myplus.finance.dto;

import com.myplus.finance.entity.PartyType;
import com.myplus.finance.entity.PaymentDirection;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** A recorded payment (ledger row) returned to callers/UIs. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentDTO {
    private Long id;
    private PaymentDirection direction;
    private PartyType partyType;
    private Long partyId;
    private String partyName;
    private BigDecimal amount;
    private String method;
    private LocalDate paidOn;
    private String reference;
    private String sourceModule;
    private String receiptNo;
    private String note;
    private LocalDateTime createdAt;
    @Builder.Default
    private List<AllocationDTO> allocations = new ArrayList<>();
}
