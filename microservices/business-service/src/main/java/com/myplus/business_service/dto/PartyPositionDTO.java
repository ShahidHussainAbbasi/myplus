package com.myplus.business_service.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * DR-3 — what one partner owes us and what we owe them, across every customer and supplier record it holds here.
 *
 * <p>READ-ONLY. Nothing is netted or posted: {@code netIfSetOff} is what WOULD remain if both sides agreed to set off,
 * and {@code setOffLimit} is the most a set-off could clear (the smaller of the two balances) — the figure DR-4 caps at.
 *
 * <p>Sources, verified against their writers (not assumed): {@code customer.due_amount} = −Σ(paid − bill) over the
 * customer's invoices, floored at 0 ({@code CustomerService.recomputeDue}); {@code vender.due_amount} = −Σ(paid − net)
 * over its purchases, floored at 0 ({@code VenderService.recomputePayable}). Both are POSITIVE amounts owed.
 */
@Data
public class PartyPositionDTO {
    private Long partyId;
    private BigDecimal receivable = BigDecimal.ZERO;   // they owe us
    private BigDecimal payable = BigDecimal.ZERO;      // we owe them
    private BigDecimal storeCredit = BigDecimal.ZERO;  // store credit we hold for them (a separate liability)
    private BigDecimal netIfSetOff = BigDecimal.ZERO;  // receivable − payable: > 0 they owe us, < 0 we owe them
    private BigDecimal setOffLimit = BigDecimal.ZERO;  // min(receivable, payable)
    private List<Line> customers = new ArrayList<>();
    private List<Line> suppliers = new ArrayList<>();

    /** One record of this partner, with its own balance — each line opens its own statement. */
    @Data
    public static class Line {
        private Long id;
        private String name;
        private BigDecimal due = BigDecimal.ZERO;
        private BigDecimal storeCredit;   // customers only
    }
}
