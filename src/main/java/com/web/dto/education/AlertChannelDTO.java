package com.web.dto.education;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
public class AlertChannelDTO implements Serializable {
	private static final long serialVersionUID = 1L;
	
//	@Autowired
//	AppUtil appUtil;
//
//	public AlertChannelDTO(AlertChannel obj) {
//		this.setC(obj.getC());
//		this.setCn(obj.getCn());
//		this.setDtStr(appUtil.getDateTimeStr(obj.getDt()));
//		this.setId(obj.getId());
//		this.setS(obj.getS());
//		this.setUId(obj.getUId());
//		this.setUt(obj.getUt());
//	}
	@Getter@Setter
	private Long id;

	@Getter@Setter
	private Long uId;

	@Getter@Setter
	private String c;

	@Getter@Setter
	private String cn;

	@Getter@Setter
	private String ut;

	@Getter@Setter
	private String dtStr;

	@Getter@Setter
	private LocalDateTime dt;

	@Getter@Setter
	private String s;
	
	@Getter@Setter
	private String pah;
	
	@Getter@Setter
	private String pam;
	
	@Getter@Setter
	private String pas;

	@Getter@Setter
	private Boolean isEmail=false;
	
	@Getter@Setter
	private Boolean isMobile=false;
	
	@Getter@Setter
	private Boolean isAll=true;

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}