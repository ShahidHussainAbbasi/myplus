package com.myplus.business_service.dto;

import lombok.Data;

import java.math.BigDecimal;

/** One tender entered at checkout (G5 payments, slice 37). */
@Data
public class TenderDTO {
    private String method;       // CASH | CARD | CREDIT | WALLET | BANK_TRANSFER
    private BigDecimal amount;
    private String reference;    // optional: card auth / txn no
}
