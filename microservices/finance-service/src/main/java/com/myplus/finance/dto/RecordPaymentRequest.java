package com.myplus.finance.dto;

import com.myplus.finance.entity.PartyType;
import com.myplus.finance.entity.PaymentDirection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Record-a-payment request. A calling module (business-service for POS today) has already allocated the money to
 * its own documents and passes the allocations here so the ledger mirrors them. finance-service stays
 * module-agnostic — it records what it's told.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RecordPaymentRequest {

    @Builder.Default
    private PaymentDirection direction = PaymentDirection.RECEIPT;

    @NotNull
    private PartyType partyType;
    private Long partyId;
    private String partyName;

    @NotNull @Positive
    private BigDecimal amount;

    private String method;
    private LocalDate paidOn;
    private String reference;
    private String sourceModule;
    private String note;

    @Builder.Default
    private List<AllocationDTO> allocations = new ArrayList<>();
}
