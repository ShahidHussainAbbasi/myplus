package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "alert_channel")

public class AlertChannel implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "agri_alertChan_gen", sequenceName = "agri_alertChan_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "agri_alertChan_gen")	
//	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "alert_channel_id", unique = true, nullable = false)
	@Getter@Setter
	private Long id;

	@Column(name = "user_id")
	@Getter@Setter
	private Long uId;

	@Column(name = "channel",nullable=false)
	@Getter@Setter
	private String c;

	@Column(name = "channel_name")
	@Getter@Setter
	private String cn;

	@Column(name = "user_type")
	@Getter@Setter
	private String ut;

	@Column(name = "dated")
	@Getter@Setter
	private LocalDateTime dt;

	@Column(name = "status")
	@Getter@Setter
	private String s;	

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}