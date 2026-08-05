package com.web.dto.business;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    private Float changeAmount;

	private List<SellDTO> sales = new ArrayList<>();	

	private Float paidAmount;

    private Float dueAmount;

    private LocalDate dueDate;

    // G5 (slice 37): checkout tenders — carried through to business-service so the sale's payment is recorded.
    private List<TenderDTO> tenders = new ArrayList<>();

    // SF-3: idempotency key (one per checkout) — carried through to business-service so a double-click / retry
    // dedups to ONE invoice. Without this field the proxy would drop it on deserialize.
    private String idempotencyKey;

    /**
     * B2B-P1 (#9): the operator has seen the credit-limit warning and chosen to continue.
     *
     * <p>This proxy binds a TYPED DTO and re-serialises it, so a field missing here is silently dropped on
     * the way to business-service — the sale would be answered CONFIRM forever and the confirmation dialog
     * would loop. Any new field on the business-service CustomerHistoryDTO needs a twin here.
     */
    private Boolean creditAcknowledged;

    // B1 (pharmacy): the prescription this sale dispenses. Same reason as the key above — the proxy deserializes
    // into this DTO, so a field that is missing here never reaches business-service and the rx guard would refuse
    // every dispense sale.
    private Long prescriptionId;

    /**
     * B2B-P3g: the invoice-level trade discount entered at checkout. Same reason as every field above — the
     * proxy binds this typed DTO and re-serialises it, so without a twin here the figure would be entered on
     * the sell screen, silently dropped in the monolith, and print as blank on the invoice.
     */
    private java.math.BigDecimal tradeDiscount;
}
