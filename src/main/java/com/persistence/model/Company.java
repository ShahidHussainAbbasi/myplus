package com.persistence.model;

import java.io.Serializable;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "company", uniqueConstraints = { @UniqueConstraint(columnNames = "company_id") })

public class Company implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "comp_gen", sequenceName = "comp_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "comp_gen")	
	@Column(name = "company_id", unique = true, nullable = false)
    @Getter@Setter
	private Long id;

	@Column(name = "user_id", nullable = false)
    @Getter@Setter
	private Long userId;

	@Column(name = "user_type")
    @Getter@Setter
	private String userType;

	@Column(name = "name", nullable = false)
    @Getter@Setter
	private String name;

	@Column(name = "email")
    @Getter@Setter
	private String email;

    @Getter@Setter
	private String mobile;

    @Getter@Setter
	private String phone;

    @Getter@Setter
	private String address;

    @Getter@Setter
    private String wattsApp;

    @Getter@Setter
    private String faceBook;

    @Getter@Setter
	private String website;

    @Lob
    @Getter@Setter
    private byte[] logo;

    @Getter@Setter
	private String description;

    @Getter@Setter
    private LocalDateTime dated;

    @Getter@Setter
	private LocalDateTime updated;


	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "OwnerDTO [id=" + id + ", name=" + name + ", description=" + description + ", mobile=" + mobile
				+ ", phone=" + phone + ", address=" + address + ", dated=" + dated + "]";
	}

}