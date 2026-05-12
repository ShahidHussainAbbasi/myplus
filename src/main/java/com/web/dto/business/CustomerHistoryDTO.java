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

    private Long id;

	private LocalDateTime dated;

	private LocalDateTime updated;

	private Long userId;

	private String userType;

	private CustomerDTO customer;

	private List<SellDTO> sales = new ArrayList<>();	

	// private SaleType saleType;	
	
	private Float paidAmount;

    private Float dueAmount;

    private LocalDate dueDate;

}
