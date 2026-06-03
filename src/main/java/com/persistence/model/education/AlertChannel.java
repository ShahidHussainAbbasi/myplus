package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

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
	@SequenceGenerator(name = "alertChan_gen", sequenceName = "alertChan_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "alertChan_gen")	
//	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "alert_channel_id", unique = true, nullable = false)
	@Getter@Setter
	private Long id;

	@Column(name = "user_id", nullable = false)
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