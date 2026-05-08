package com.web.dto.business;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.persistence.model.business.enums.SaleType;

import lombok.Data;

/**
 * 
 */
@Data
public class CustomerHistoryDTO {

    private Long id;

	private LocalDateTime dated;

	private LocalDateTime updated;

	private Long userId;

	private String userType;

	private CustomerDTO customerDTO;

	private List<SellDTO> sales = new ArrayList<>();	

	private SaleType saleType;	

}
