package com.myplus.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesAnalyticsDTO {
    private String month;
    private BigDecimal revenue;
    private int salesCount;
    private BigDecimal avgOrderValue;
}
