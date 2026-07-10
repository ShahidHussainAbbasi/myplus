package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A read view of one ledger payment, returned by finance-service GET /payments. Consumers (business-service
 * statements) deserialize this; {@code direction} is a plain String ("RECEIPT"/"DISBURSEMENT") so callers need no
 * finance enums. Only the fields a statement/reconciliation needs are carried.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentView {
    private Long id;
    private String direction;      // RECEIPT | DISBURSEMENT
    private String partyType;      // CUSTOMER | VENDOR | ...
    private Long partyId;
    private BigDecimal amount;
    private String method;
    private LocalDate paidOn;
    private String reference;
    private String receiptNo;      // RCPT-###### | PV-######
}
