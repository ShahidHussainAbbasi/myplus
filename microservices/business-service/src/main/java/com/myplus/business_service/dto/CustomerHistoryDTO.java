package com.myplus.business_service.dto;
import java.math.BigDecimal;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

/**
 * 
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerHistoryDTO {

    private Long customer_history_id;

	private LocalDateTime dated;

	private LocalDateTime updated;

	private Long userId;

	private String userType;

	private CustomerDTO customer;

	private Float receivedAmount;

	private List<SellDTO> sales = new ArrayList<>();

	private BigDecimal paidAmount;

    private BigDecimal dueAmount;

    private LocalDate dueDate;

    private Long invoiceSeq;     // per-org running number (slice 22)

    /**
     * Pharmacy (review B1): the prescription this sale dispenses, when the cashier started from Dispense. Its
     * PRESENCE is what lets a prescription-only line through the sell guard; whether the script actually covers
     * the basket is reconciled straight after by pharma-service's dispense call (which warns on off-script and
     * capped quantities) — verifying it here would put pharma-service on the checkout path.
     */
    private Long prescriptionId;

    private String invoiceNo;    // display invoice number, e.g. INV-000123

    // G3 (slice 35): invoice tax summary for the receipt + tax report.
    private BigDecimal subTotal;

    private BigDecimal taxTotal;

    private BigDecimal grandTotal;

    // G5 (slice 37): tenders entered at checkout (in) + the settled payment summary (out).
    private List<TenderDTO> tenders = new ArrayList<>();

    private String paymentMode;

    private BigDecimal tenderedAmount;

    private BigDecimal changeAmount;

    private BigDecimal storeCreditApplied;   // SF-5 Model B: store credit redeemed on this sale (for the receipt)

    // SF-3: client-supplied idempotency key (one per checkout attempt). addSell dedups on (org, key) so a
    // double-click / network retry records ONE invoice instead of two. Null for legacy callers.
    private String idempotencyKey;

    // G6 (slice 38): receipt header bits from the org tax policy (not persisted on the invoice).
    private String taxLabel;

    private String taxRegNo;

    // common-settings (pos.receipt.showTaxBreakdown): whether the receipt should list tax per rate. The owner
    // toggles this on the Configuration screen; the client honours it (default true when absent = back-compat).
    private Boolean showTaxBreakdown;

    // common-settings (pos.receipt.showPromo): whether to print the "Powered by MaxTheService" footer.
    // OFF unless the org opted in — this appears on a document our customer hands to THEIR customer, so
    // absent/null means off (unlike a safety flag, where absent means on).
    private Boolean showPromo;

    /**
     * B2B P1 (#9): the operator has SEEN the credit-limit warning and chosen to continue.
     *
     * <p>Inbound only. Under {@code warn} the first submit of an over-limit sale is answered {@code CONFIRM}
     * with nothing written; the client asks, and re-submits with this set. Under {@code block} it is ignored
     * entirely — that is the whole difference between the two policies: nobody on the till can consent past
     * {@code block}.
     */
    private Boolean creditAcknowledged;

    /**
     * Things the cashier must be TOLD about a sale that still went through — currently the zero/negative
     * margin warning (#3). Server-populated on the way out; ignored on the way in.
     *
     * <p>Same pattern as {@code PrescriptionDTO.warnings}: the money has already changed hands by the time
     * these are raised, so anything the system could not enforce has to be said out loud rather than only
     * logged. Initialised so callers never null-check.
     */
    private java.util.List<String> warnings = new java.util.ArrayList<>();
}
