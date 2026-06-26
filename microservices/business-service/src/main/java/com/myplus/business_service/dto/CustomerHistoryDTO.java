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

    // G6 (slice 38): receipt header bits from the org tax policy (not persisted on the invoice).
    private String taxLabel;

    private String taxRegNo;
}
