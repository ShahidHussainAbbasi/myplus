package com.web.dto.business;

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

	private Float paidAmount;

    private Float dueAmount;

    private LocalDate dueDate;

    // G5 (slice 37): checkout tenders — carried through to business-service so the sale's payment is recorded.
    private List<TenderDTO> tenders = new ArrayList<>();
}
