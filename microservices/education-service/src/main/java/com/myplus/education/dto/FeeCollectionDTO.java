package com.myplus.education.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class FeeCollectionDTO {
    private Long id;
    private Long userId;
    private String en;     // enroll no
    private String sn;     // student name (resolved)
    private String dt;     // discount type
    private Integer d;     // discount
    private Integer dd;    // due day of month
    private Integer da;    // due amount
    private Integer f;     // fee
    private Integer fp;    // fee paid
    private String pdStr;  // payment date
    private Integer od;    // other dues
    private String odd;    // other dues description
    private String p;      // payee
    private String rb;     // received by
    private String ri;     // received in
}
