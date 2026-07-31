package com.myplus.common.subledger;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** F2: one party's aging row for the report grid — the 4 buckets + total. Party = a customer (AR) or vendor (AP). */
@Data @NoArgsConstructor @AllArgsConstructor
public class PartyAgingDTO {
    private Long partyId;
    private String partyName;
    private BigDecimal b0_30;
    private BigDecimal b31_60;
    private BigDecimal b61_90;
    private BigDecimal b90plus;
    private BigDecimal total;
}
