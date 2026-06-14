package com.myplus.welfare.dto;

public class DonatorDTO {

    private Long userId = null;
    private String userType = null;

    private Long id = null;
    private String name = null;
    private String mobile = null;
    private String fName = null;
    private String address = null;
    private Float amount = null;
    private String receivedBy = null;
    private Boolean showMe = false;
    private String datedStr;
    private String updatedStr;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUserType() { return userType; }
    public void setUserType(String userType) { this.userType = userType; }

    public Boolean getShowMe() { return showMe; }
    public void setShowMe(Boolean showMe) { this.showMe = showMe; }

    public String getDatedStr() { return datedStr; }
    public void setDatedStr(String datedStr) { this.datedStr = datedStr; }

    public String getUpdatedStr() { return updatedStr; }
    public void setUpdatedStr(String updatedStr) { this.updatedStr = updatedStr; }
}
