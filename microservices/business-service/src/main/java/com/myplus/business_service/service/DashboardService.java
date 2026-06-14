package com.myplus.business_service.service;

import com.myplus.business_service.dto.BusinessDTOs.DashboardChartsDTO;
import com.myplus.business_service.dto.BusinessDTOs.DashboardStatsDTO;
import com.myplus.business_service.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final CompanyRepository companyRepository;
    private final VenderRepository venderRepository;
    private final CustomerRepository customerRepository;
    private final ItemRepository itemRepository;
    private final SellRepository sellRepository;
    private final PurchaseRepository purchaseRepository;

    public DashboardStatsDTO getStats(Long userId) {
        YearMonth now = YearMonth.now();
        LocalDateTime monthStart = now.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = now.atEndOfMonth().atTime(23, 59, 59);

        Double salesRaw = sellRepository.sumByUserAndDateRange(userId, monthStart, monthEnd);
        Double purchasesRaw = purchaseRepository.sumByUserAndDateRange(userId, monthStart, monthEnd);
        BigDecimal monthlySales = salesRaw != null ? BigDecimal.valueOf(salesRaw) : BigDecimal.ZERO;
        BigDecimal monthlyPurchases = purchasesRaw != null ? BigDecimal.valueOf(purchasesRaw) : BigDecimal.ZERO;
        BigDecimal monthlyRevenue = monthlySales.subtract(monthlyPurchases);

        return DashboardStatsDTO.builder()
                .companiesCount(companyRepository.countByUserId(userId))
                .vendersCount(venderRepository.countByUserId(userId))
                .customersCount(customerRepository.countByUserId(userId))
                .itemsCount(itemRepository.countByUserId(userId))
                .monthlySales(monthlySales != null ? monthlySales : BigDecimal.ZERO)
                .monthlyRevenue(monthlyRevenue)
                .build();
    }

    public DashboardChartsDTO getCharts(Long userId) {
        int year = YearMonth.now().getYear();
        List<Object[]> monthly = sellRepository.monthlySales(userId, year);
        List<Map<String, Object>> monthlyRevenue = monthly.stream().map(row -> {
            Map<String, Object> m = new HashMap<>();
            m.put("month", row[0]);
            m.put("amount", row[1]);
            return m;
        }).toList();

        List<Object[]> top = sellRepository.topSellingItems(userId, PageRequest.of(0, 10));
        List<Map<String, Object>> topItems = top.stream().map(row -> {
            Map<String, Object> m = new HashMap<>();
            m.put("itemId", row[0]);
            m.put("quantity", row[1]);
            return m;
        }).toList();

        return DashboardChartsDTO.builder()
                .monthlyRevenue(monthlyRevenue)
                .dailyRevenue(List.of())
                .topItems(topItems)
                .customerSales(List.of())
                .build();
    }
}
