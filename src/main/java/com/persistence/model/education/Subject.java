package com.persistence.model.education;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;


/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(
        name = "subject"
)	

public class Subject implements Serializable {
	private static final long serialVersionUID = 1L;
	
	@Id
	@Column(name = "name", unique = true, nullable = false)
	private String name;

    @GeneratedValue(strategy=GenerationType.IDENTITY)
    @Column(name="subject_id", unique = true, nullable = false)
	private Long id;

	@Column(name="user_id")
	private Long userId;

	@Column(name="user_type")
	private String userType;
	
	private String code;

	private String dated;
	
	@Column(name="school_id")
	private Long schoolId = null;

	@Column(name="class_id")
	private Long classid = null;

	@Column(name="student_id")
	private Long studentId = null;

	@Column(name="room_id")
	private Long roomId = null;

	private Boolean status;

	
	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}


	/**
	 * @param name the name to set
	 */
	public void setName(String name) {
		this.name = name;
	}


	/**
	 * @return the id
	 */
	public Long getId() {
		return id;
	}


	/**
	 * @param id the id to set
	 */
	public void setId(Long id) {
		this.id = id;
	}


	/**
	 * @return the userId
	 */
	public Long getUserId() {
		return userId;
	}


	/**
	 * @param userId the userId to set
	 */
	public void setUserId(Long userId) {
		this.userId = userId;
	}


	/**
	 * @return the userType
	 */
	public String getUserType() {
		return userType;
	}


	/**
	 * @param userType the userType to set
	 */
	public void setUserType(String userType) {
		this.userType = userType;
	}


	/**
	 * @return the code
	 */
	public String getCode() {
		return code;
	}


	/**
	 * @param code the code to set
	 */
	public void setCode(String code) {
		this.code = code;
	}


	/**
	 * @return the dated
	 */
	public String getDated() {
		return dated;
	}


	/**
	 * @param dated the dated to set
	 */
	public void setDated(String dated) {
		this.dated = dated;
	}


	/**
	 * @return the schoolId
	 */
	public Long getSchoolId() {
		return schoolId;
	}


	/**
	 * @param schoolId the schoolId to set
	 */
	public void setSchoolId(Long schoolId) {
		this.schoolId = schoolId;
	}


	/**
	 * @return the classid
	 */
	public Long getClassid() {
		return classid;
	}


	/**
	 * @param classid the classid to set
	 */
	public void setClassid(Long classid) {
		this.classid = classid;
	}


	/**
	 * @return the roomId
	 */
	public Long getRoomId() {
		return roomId;
	}


	/**
	 * @param roomId the roomId to set
	 */
	public void setRoomId(Long roomId) {
		this.roomId = roomId;
	}


	/**
	 * @return the status
	 */
	public Boolean getStatus() {
		return status;
	}


	/**
	 * @param status the status to set
	 */
	public void setStatus(Boolean status) {
		this.status = status;
	}


	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}


	/**
	 * @return the studentId
	 */
	public Long getStudentId() {
		return studentId;
	}


	/**
	 * @param studentId the studentId to set
	 */
	public void setStudentId(Long studentId) {
		this.studentId = studentId;
	}


	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "Subject [name=" + name + ", id=" + id + ", userId=" + userId + ", userType=" + userType + ", code="
				+ code + ", dated=" + dated + ", schoolId=" + schoolId + ", classid=" + classid + ", roomId=" + roomId
				+ ", status=" + status + "]";
	}
	
}