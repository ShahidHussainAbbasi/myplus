package com.myplus.welfare.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "donator")
public class Donator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name = null;

    private Long userId = null;
    @jakarta.persistence.Column(name = "organization_id")
    private Long organizationId = null;       // tenant scope (from gateway X-Org-Id); user_id kept as audit
    private String userType = null;
    private String mobile = null;
    private String fName = null;
    private String address = null;
    private Float amount = null;
    private String receivedBy = null;
    private LocalDateTime dated;
    private LocalDateTime updated;
    private Boolean showMe = null;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }

    public String getUserType() { return userType; }
    public void setUserType(String userType) { this.userType = userType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }

    public String getfName() { return fName; }
    public void setfName(String fName) { this.fName = fName; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public Float getAmount() { return amount; }
    public void setAmount(Float amount) { this.amount = amount; }

    public String getReceivedBy() { return receivedBy; }
    public void setReceivedBy(String receivedBy) { this.receivedBy = receivedBy; }

    public LocalDateTime getDated() { return dated; }
    public void setDated(LocalDateTime dated) { this.dated = dated; }

    public LocalDateTime getUpdated() { return updated; }
    public void setUpdated(LocalDateTime updated) { this.updated = updated; }

    public Boolean isShowMe() { return showMe; }
    public Boolean getShowMe() { return showMe; }
    public void setShowMe(Boolean showMe) { this.showMe = showMe; }
}
