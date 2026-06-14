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

    private Float changeAmount;

	private List<SellDTO> sales = new ArrayList<>();	

	private BigDecimal paidAmount;

    private BigDecimal dueAmount;

    private LocalDate dueDate;

    private Long invoiceSeq;     // per-org running number (slice 22)

    private String invoiceNo;    // display invoice number, e.g. INV-000123
}
