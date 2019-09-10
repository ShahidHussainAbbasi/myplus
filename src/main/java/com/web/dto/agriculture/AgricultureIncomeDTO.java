/**
 * 
 */
package com.web.dto.agriculture;

import lombok.Getter;
import lombok.Setter;

/**
 * @author Shahid
 *
 */
public class AgricultureIncomeDTO extends AgricultureBaseDTO{

	@Setter @Getter
	private String incomeName = null;
	@Setter @Getter
	private String incomeType = null;

}
