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
public class AgricultureBaseDTO {

	@Setter @Getter
	private Long userId = null;
	@Setter @Getter
	private String userType = null;
	@Setter @Getter
	private Long id = null;
	@Setter @Getter
	private Long landId = null;
	@Setter @Getter
	private String landName = null;
	@Setter @Getter
	private String landUnit = null;
	@Setter @Getter
	private String totalLandUnit = null;
	@Setter @Getter
	private String cropName = null;
	@Setter @Getter
	private String cropType = null;
	@Setter @Getter
	private Float amount = null;
	@Setter @Getter
	private String datedStr = null;
	@Setter @Getter
	private String updatedStr = null;
	@Setter @Getter
	private String description = null;

}
