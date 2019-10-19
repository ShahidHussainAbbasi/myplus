package com.web.dto.education;

import java.io.Serializable;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Builder
public class DashboardDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	@Getter@Setter
	private String lastMonth;

	@Getter@Setter
	private Long freshStudent;

	@Getter@Setter
	private Long allStudent;


	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}