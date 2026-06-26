package com.web.dto.business;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

/**
 * One checkout tender (G5 payments, slice 37) — carried through the monolith's addSell/updateSell proxy so it
 * round-trips to business-service. method = CASH|CARD|CREDIT|WALLET|BANK_TRANSFER.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenderDTO {
    private String method;
    private BigDecimal amount;
    private String reference;
}
