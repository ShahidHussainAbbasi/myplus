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
public class AgricultureExpenseDTO extends AgricultureBaseDTO{

	@Setter @Getter
	private String expenseName = null;
	@Setter @Getter
	private String expenseType = null;

}
