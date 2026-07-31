package com.myplus.education.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class FeeCollectionDTO {
    private Long id;
    private Long userId;
    private String enrollNo;     // enroll no
    private String sn;     // student name (resolved)
    private String discountType;     // discount type
    private Integer discount;     // discount
    private Integer dueDayOfMonth;    // due day of month
    private Integer dueAmount;    // due amount
    private Integer fee;     // fee
    private Integer feePaid;    // fee paid
    private String pdStr;  // payment date
    private Integer otherDues;    // other dues
    private String otherDuesDescription;    // other dues description
    private String payee;      // payee
    private String receivedBy;     // received by
    private String receivedIn;
    // Slice 0.2a: these three round-trip the form's V. Fee, Check No and Balance. They were absent from this DTO
    // entirely, so addFc silently dropped them and no read could return a balance — which is what an open
    // receivable is made of.
    private Integer vehicleFee;
    private String checkNo;
    private Integer dueBalance;     // received in
}
