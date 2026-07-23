package com.myplus.welfare.dto;

public class DonationDTO {

    private Long id = null;
    private Long donatorId;
    private String donatorName;
    private Long userId = null;
    private String userType = null;
    private java.math.BigDecimal amount = null;   // money: BigDecimal (matches the entity)
    private String receivedBy = null;
    private String datedStr = null;
    private String updatedStr = null;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDonatorId() { return donatorId; }
    public void setDonatorId(Long donatorId) { this.donatorId = donatorId; }

    public String getDonatorName() { return donatorName; }
    public void setDonatorName(String donatorName) { this.donatorName = donatorName; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUserType() { return userType; }
    public void setUserType(String userType) { this.userType = userType; }

    public java.math.BigDecimal getAmount() { return amount; }
    public void setAmount(java.math.BigDecimal amount) { this.amount = amount; }

    public String getReceivedBy() { return receivedBy; }
    public void setReceivedBy(String receivedBy) { this.receivedBy = receivedBy; }

    public String getDatedStr() { return datedStr; }
    public void setDatedStr(String datedStr) { this.datedStr = datedStr; }

    public String getUpdatedStr() { return updatedStr; }
    public void setUpdatedStr(String updatedStr) { this.updatedStr = updatedStr; }
}
