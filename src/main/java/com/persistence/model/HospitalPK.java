package com.persistence.model;

import java.io.Serializable;
import javax.persistence.*;

/**
 * The primary key class for the hospital database table.
 * 
 */
@Embeddable
public class HospitalPK implements Serializable {
	//default serial version id, required for serializable classes.
	private static final long serialVersionUID = 1L;

	@Column(name="hospital_id")
	private int hospitalId;

	private String name;

	public HospitalPK() {
	}
	public int getHospitalId() {
		return this.hospitalId;
	}
	public void setHospitalId(int hospitalId) {
		this.hospitalId = hospitalId;
	}
	public String getName() {
		return this.name;
	}
	public void setName(String name) {
		this.name = name;
	}

	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof HospitalPK)) {
			return false;
		}
		HospitalPK castOther = (HospitalPK)other;
		return 
			(this.hospitalId == castOther.hospitalId)
			&& this.name.equals(castOther.name);
	}

	public int hashCode() {
		final int prime = 31;
		int hash = 17;
		hash = hash * prime + this.hospitalId;
		hash = hash * prime + this.name.hashCode();
		
		return hash;
	}
}