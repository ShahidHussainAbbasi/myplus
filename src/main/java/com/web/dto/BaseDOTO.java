/**
 * 
 */
package com.web.dto;

/**
 * @author sabbasi
 *
 */
public class BaseDOTO {

    private String message;
    private String error;
    private String status = "SUCCESS";
	public String getMessage() {
		return message;
	}
	public void setMessage(String message) {
		this.message = message;
	}
	public String getError() {
		return error;
	}
	public void setError(String error) {
		this.error = error;
	}
	public String getStatus() {
		return status;
	}
	public void setStatus(String status) {
		this.status = status;
	}
    
    
}
