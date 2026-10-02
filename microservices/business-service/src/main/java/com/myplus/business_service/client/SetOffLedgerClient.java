package com.myplus.business_service.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import com.myplus.commerce.contracts.dto.PaymentAllocationRef;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DR-4 — finance-service records BOTH legs of a set-off in ONE transaction: a RECEIPT from the customer and a
 * DISBURSEMENT to the supplier (method SETOFF), journals Dr 1900 / Cr 1100 and Dr 2000 / Cr 1900. Deduplicated by
 * {@code idempotencyKey}: a repeat answers the first document ({@code replay = true}) and posts nothing.
 *
 * <p>Deliberately NOT on the shared {@code FinanceClient}: unlike Receive Payment's best-effort record, a failure here
 * must FAIL the set-off — the caller lets the exception roll its own transaction back, so nothing moves anywhere.
 *
 * <p>⚠ Wire twin: finance-service declares the same field names. A field added on one side only is dropped in silence
 * (the GL-outbox lesson) — the DR-4 gate asserts the journal amounts, which is what would expose it.
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface SetOffLedgerClient {

    @PostExchange("/internal/finance/setoffs")
    Result record(@RequestBody Request request);

    @PostExchange("/internal/finance/setoffs/reverse")
    Result reverse(@RequestBody ReverseRequest request);

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    class Request {
        private String idempotencyKey;
        private String setOffNo;
        private Long customerId;
        private String customerName;
        private Long venderId;
        private String venderName;
        private BigDecimal amount;
        private LocalDate paidOn;
        private String reference;
        private List<PaymentAllocationRef> customerAllocations;
        private List<PaymentAllocationRef> vendorAllocations;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    class ReverseRequest {
        private String idempotencyKey;   // the set-off's own key — identifies what to reverse
        private String reversalKey;      // dedups the reversal itself
        private String setOffNo;
        private String reason;
        private LocalDate reversedOn;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    class Result {
        private String receiptNo;
        private String voucherNo;
        private boolean replay;
    }
}
