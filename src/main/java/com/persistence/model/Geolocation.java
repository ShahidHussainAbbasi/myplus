package com.persistence.model;

import java.io.Serializable;
import javax.persistence.*;


/**
 * The persistent class for the geolocation database table.
 * 
 */
@Entity
@Table(name = "geolocation")
public class Geolocation implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy=GenerationType.AUTO)
	private String id;

	private String address;

	private String boundingbox;

	private String city;

	private String country;

	@Column(name="country_code")
	private String countryCode;

	@Column(name="display_name")
	private String displayName;

	private String lat;

	private String lng;

	private String postcode;

	private String state;

	private String town;

	public Geolocation() {
	}

	public String getId() {
		return this.id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getAddress() {
		return this.address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public String getBoundingbox() {
		return this.boundingbox;
	}

	public void setBoundingbox(String boundingbox) {
		this.boundingbox = boundingbox;
	}

	public String getCity() {
		return this.city;
	}

	public void setCity(String city) {
		this.city = city;
	}

	public String getCountry() {
		return this.country;
	}

	public void setCountry(String country) {
		this.country = country;
	}

	public String getCountryCode() {
		return this.countryCode;
	}

	public void setCountryCode(String countryCode) {
		this.countryCode = countryCode;
	}

	public String getDisplayName() {
		return this.displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getLat() {
		return this.lat;
	}

	public void setLat(String lat) {
		this.lat = lat;
	}

	public String getLng() {
		return this.lng;
	}

	public void setLng(String lng) {
		this.lng = lng;
	}

	public String getPostcode() {
		return this.postcode;
	}

	public void setPostcode(String postcode) {
		this.postcode = postcode;
	}

	public String getState() {
		return this.state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public String getTown() {
		return this.town;
	}

	public void setTown(String town) {
		this.town = town;
	}

}