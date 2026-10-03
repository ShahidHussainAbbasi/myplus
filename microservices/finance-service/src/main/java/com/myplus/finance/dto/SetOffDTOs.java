package com.myplus.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DR-4 — the set-off wire contract. ⚠ TWIN of business-service {@code SetOffLedgerClient.Request / ReverseRequest /
 * Result}: the field names must match exactly, or a field is dropped in silence. The DR-4 gate asserts the journal
 * amounts and both document numbers, which is what would expose a drift.
 */
public final class SetOffDTOs {

    private SetOffDTOs() { }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Request {
        private String idempotencyKey;
        private String setOffNo;
        private Long customerId;
        private String customerName;
        private Long venderId;
        private String venderName;
        private BigDecimal amount;
        private LocalDate paidOn;
        private String reference;
        @Builder.Default private List<AllocationDTO> customerAllocations = new ArrayList<>();
        @Builder.Default private List<AllocationDTO> vendorAllocations = new ArrayList<>();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ReverseRequest {
        private String idempotencyKey;
        private String reversalKey;
        private String setOffNo;
        private String reason;
        private LocalDate reversedOn;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Result {
        private String receiptNo;
        private String voucherNo;
        private boolean replay;
    }
}
