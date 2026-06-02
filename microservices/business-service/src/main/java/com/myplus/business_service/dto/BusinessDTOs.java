package com.myplus.business_service.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class BusinessDTOs {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CompanyDTO {
        private Long id;
        private String name;
        private String phone;
        private String email;
        private String address;
        private Long userId;
        private LocalDateTime createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VenderDTO {
        private Long id;
        private String name;
        private Long companyId;
        private String phone;
        private String mobile;
        private String email;
        private String address;
        private Long userId;
        private LocalDateTime createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CustomerDTO {
        private Long customerId;
        private String name;
        private String contact;
        private String email;
        private String address;
        private BigDecimal dueAmount;
        private BigDecimal paidAmount;
        private LocalDate dueDate;
        private Long userId;
        public Long getId() { return customerId; }
        public void setId(Long id) { this.customerId = id; }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StockDTO {
        private Long stockId;
        private Long itemId;
        private BigDecimal bpurchaseRate;
        private BigDecimal bsellRate;
        private BigDecimal bpurchaseDiscount;
        private String bpurchaseDiscountType;
        private BigDecimal bsellDiscount;
        private String bsellDiscountType;
        private LocalDate bexpDate;
        private Float stock;
        private BigDecimal srp;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ItemDTO {
        private Long id;
        private String iname;
        private String icode;
        private String idesc;
        private Long companyId;
        private Long venderId;
        private Long userId;
        private StockDTO stock;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PurchaseDTO {
        private Long purchaseId;
        private Long stockId;
        private Float quantity;
        private BigDecimal totalAmount;
        private BigDecimal netAmount;
        private String purchaseInvoiceNo;
        private Long userId;
        private LocalDateTime dated;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SellDTO {
        private Long sellId;
        private Long stockId;
        private Long customerHistoryId;
        private Long customerId;
        private Float quantity;
        private BigDecimal totalAmount;
        private BigDecimal netAmount;
        private Long userId;
        private LocalDateTime dated;
        private BigDecimal paidAmount;
        private BigDecimal dueAmount;
        private LocalDate dueDate;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DashboardStatsDTO {
        private long companiesCount;
        private long vendersCount;
        private long customersCount;
        private long itemsCount;
        private BigDecimal monthlySales;
        private BigDecimal monthlyRevenue;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DashboardChartsDTO {
        private List<Map<String, Object>> monthlyRevenue;
        private List<Map<String, Object>> dailyRevenue;
        private List<Map<String, Object>> topItems;
        private List<Map<String, Object>> customerSales;
    }
}
