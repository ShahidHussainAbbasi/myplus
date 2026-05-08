package com.web.dto.business;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;

import com.persistence.model.business.Sell;
import com.persistence.model.business.enums.CustomerType;

import lombok.Data;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Data
public class CustomerDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long id;

	private String name;

	private CustomerType customerType;	

	private String contact;

	private String address;

	private Float paidAmount;

    private Float dueAmount;

    private LocalDate dueDate;
	
	private LocalDateTime dated;

	private LocalDateTime updated;

	private CustomerHistoryDTO customerHistory;

	private Long userId;

	private String userType;

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}