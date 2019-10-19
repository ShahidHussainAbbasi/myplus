package com.web.dto;

import java.io.Serializable;

import org.springframework.web.multipart.MultipartFile;

import com.validation.ValidEmail;
import com.validation.ValidMobileNumber;
import com.validation.ValidateEmpty;

import lombok.Getter;
import lombok.Setter;


public class CompanyDTO implements Serializable {
	private static final long serialVersionUID = 1L;

    @Getter@Setter
	private Long id;
    @Getter@Setter
	private Long userId;
    @Getter@Setter
	private String userType;
	@ValidateEmpty
    @Getter@Setter
	private String name;
//    @Getter@Setter
//	private String brands;
	@ValidMobileNumber
    @Getter@Setter
	private String mobile;
	@ValidateEmpty
    @Getter@Setter
	private String phone;
	@ValidateEmpty
    @Getter@Setter
	private String address;
    @Getter@Setter
	private String website;
    @Getter@Setter
    private String wattsApp;

    @Getter@Setter
    private String faceBook;

    @Getter@Setter
    private String company;
    
    @Getter@Setter
    private MultipartFile logo;
	
	@ValidEmail
    @Getter@Setter
	private String email;
	
    @Getter@Setter
	private String datedStr;
    
    @Getter@Setter
	private String updatedStr;

    @Override
	public String toString() {
		return "CompanyDTO [id=" + id + ", userId=" + userId + ", userType=" + userType + ", name=" + name + ", mobile="
				+ mobile + ", phone=" + phone + ", address=" + address + ", website=" + website + ", wattsApp="
				+ wattsApp + ", faceBook=" + faceBook + ", company=" + company + ", logo=" + logo + ", email=" + email
				+ ", datedStr=" + datedStr + ", updatedStr=" + updatedStr + "]";
	}


}