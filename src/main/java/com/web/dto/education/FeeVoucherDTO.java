package com.web.dto.education;

import java.time.LocalDate;

import lombok.Data;

/**
 * 
 */

/**
 * @author sabbasi
 *
 */
@Data
public class FeeVoucherDTO {

	private String vb = null;
	private String vi = null;
	private String inclExclSelected = null;
	private Short vp = 1;
	private Short rb = -1;//report by
	private String rbs = null;//report by student status
	private String ri = null;//report input
	private Short rp = 0;
	private String redStr = null;
	private String rsdStr = null;
	private String sdStr = null;
	private String edStr = null;
	private LocalDate sd = LocalDate.now();
	private LocalDate ed = LocalDate.now();

	
}
