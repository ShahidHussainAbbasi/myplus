package com.myplus.business_service.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Sell;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;

@RestController
public class BusinessDashboardController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private RequestUtil requestUtil;

    @Autowired
    private AppUtil appUtil;

    @Autowired
    private VenderRepo venderRepo;

    /*
     * Repositories, not the services, for the COUNT/SUM reads below.
     *
     * The services expose findScoped(...) which returns entities; there is no count on them, and adding one
     * would be a pass-through that earns nothing. The aggregates are a repository concern — they answer with
     * a number, never a row — so the controller reaches the repository directly for them and keeps using the
     * services for everything that still deals in objects.
     */
    @Autowired
    private com.myplus.business_service.repository.CompanyRepo companyRepo;

    @Autowired
    private com.myplus.business_service.repository.CustomerRepo customerRepo;

    @Autowired
    private com.myplus.business_service.repository.SellRepo sellRepo;

    @Autowired
    private CustomerHistoryRepo customerHistoryRepo;

    @Autowired
    private com.myplus.commerce.contracts.client.CatalogClient catalogClient;   // M4d: top-item names from catalog

    @GetMapping("/getBusinessDashboardStats")
    @ResponseBody
    public GenericResponse getBusinessDashboardStats(HttpServletRequest request) {
        try {
            AuthenticatedUser user = requestUtil.getCurrentUser();
            Long userId = user.getUserId();
            Long orgId = user.getOrganizationId();

            // org-scoped counts (consistent with the findScoped lists; were userId-only Example probes
            // that ignored the active tenant — wrong after an org-switch / for a teammate's rows).
            /*
             * COUNT in SQL, not size() over a hydrated list.
             *
             * These three lines used to be findScoped(...).size() — which loads every company, vendor and
             * customer of the tenant into JPA entities, and then discards them to keep an integer. The
             * customer one is the same read that returns ~196KB elsewhere. That is most of why this endpoint
             * answered in ~640ms for a 183-byte payload, repeatably, warm: nothing was cached because
             * nothing needed to be — the work simply should not have been done.
             *
             * Each countScoped carries a character-for-character copy of its findScoped predicate, NULL-org
             * fallback included. That is the whole risk here: a count scoped even slightly differently gives
             * a plausible number that is quietly wrong on a screen nobody would think to check.
             */
            long companyCount  = companyRepo.countScoped(orgId, userId);
            long venderCount   = venderRepo.countScoped(orgId, userId);
            long customerCount = customerRepo.countScoped(orgId, userId);
            // M4e.c (slice 103): the "items" KPI now counts catalog Products (the single master), not local Items.
            long itemCount = 0;
            try { itemCount = catalogClient.countProducts(); }
            catch (Exception ex) { LOGGER.warn("M4e.c: catalog product count failed; items KPI shows 0", ex); }

            LocalDateTime startOfMonth = appUtil.firstDateTimeOfMonth();
            LocalDateTime endOfMonth = appUtil.lastDateTimeOfMonth();
            // Same again for the period figures: one aggregate row instead of every Sell in the month
            // hydrated, counted and summed in a Java stream.
            Object[] agg = sellRepo.sumSellByDates(startOfMonth, endOfMonth, user.getOrganizationId(), userId);
            // A single-row aggregate arrives as Object[] or as Object[]{Object[]} depending on the provider;
            // normalise it the way ShiftService already does rather than inventing a second idiom.
            Object[] row = (agg != null && agg.length == 1 && agg[0] instanceof Object[]) ? (Object[]) agg[0] : agg;
            long sellCount = (row != null && row.length > 0 && row[0] != null) ? ((Number) row[0]).longValue() : 0L;
            // coalesce(...,0) in the query means this is never null; the guard keeps an empty period at 0.0,
            // which is exactly what summing an empty stream used to produce.
            double monthlyRevenue = (row != null && row.length > 1 && row[1] != null)
                    ? ((Number) row[1]).doubleValue() : 0.0;

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("companies", companyCount);
            stats.put("venders", venderCount);
            stats.put("customers", customerCount);
            stats.put("items", itemCount);
            stats.put("monthlySales", sellCount);
            stats.put("monthlyRevenue", String.format("%.0f", monthlyRevenue));

            return new GenericResponse("SUCCESS", "stats", stats);
        } catch (Exception e) {
            LOGGER.error("getBusinessDashboardStats error: " + e.getMessage(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @GetMapping("/getDashboardChartData")
    @ResponseBody
    public GenericResponse getDashboardChartData(HttpServletRequest request) {
        try {
            AuthenticatedUser user = requestUtil.getCurrentUser();
            Long userId = user.getUserId();
            LocalDateTime now = LocalDateTime.now();

            // --- 6-month revenue & sales trend ---
            DateTimeFormatter monthKey = DateTimeFormatter.ofPattern("yyyy-MM");
            DateTimeFormatter monthLabel = DateTimeFormatter.ofPattern("MMM yy");
            Map<String, Double> revenueByMonth = new LinkedHashMap<>();
            Map<String, Integer> salesByMonth = new LinkedHashMap<>();
            List<String> monthLabels = new ArrayList<>();
            for (int i = 5; i >= 0; i--) {
                LocalDateTime m = now.minusMonths(i);
                String key = m.format(monthKey);
                monthLabels.add(m.format(monthLabel));
                revenueByMonth.put(key, 0.0);
                salesByMonth.put(key, 0);
            }
            LocalDateTime sixMonthsAgoStart = now.minusMonths(5)
                .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            // GROUP BY in SQL instead of six months of Sell entities bucketed by a merge loop. The zeroed
            // map above still governs which months appear, so a month with no sales stays 0 rather than
            // vanishing from the chart.
            for (Object[] r : sellRepo.monthlyTrendScoped(sixMonthsAgoStart, appUtil.lastDateTimeOfMonth(),
                                                          user.getOrganizationId(), userId)) {
                if (r == null || r.length < 4 || r[0] == null || r[1] == null) continue;
                String key = String.format("%04d-%02d", ((Number) r[0]).intValue(), ((Number) r[1]).intValue());
                if (!revenueByMonth.containsKey(key)) continue;   // outside the six shown — same guard as before
                salesByMonth.put(key, r[2] == null ? 0 : ((Number) r[2]).intValue());
                revenueByMonth.put(key, r[3] == null ? 0.0 : ((Number) r[3]).doubleValue());
            }

            // --- daily revenue this month ---
            LocalDateTime startOfMonth = appUtil.firstDateTimeOfMonth();
            LocalDateTime endOfMonth = appUtil.lastDateTimeOfMonth();
            // The month's sells are no longer loaded at all: the two things built from them — daily revenue
            // and the top-5 products — are each their own GROUP BY below.
            int daysInMonth = now.toLocalDate().lengthOfMonth();
            double[] dailyRev = new double[daysInMonth];
            for (Object[] r : sellRepo.dailyRevenueScoped(startOfMonth, endOfMonth,
                                                          user.getOrganizationId(), userId)) {
                if (r == null || r.length < 2 || r[0] == null) continue;
                int d = ((Number) r[0]).intValue() - 1;
                // Defensive: a row dated outside the array cannot land here given the range predicate, but an
                // out-of-bounds write would take the whole dashboard down rather than lose one bar.
                if (d < 0 || d >= daysInMonth) continue;
                dailyRev[d] = r[1] == null ? 0.0 : ((Number) r[1]).doubleValue();
            }
            List<Integer> dayLabels = new ArrayList<>();
            List<Double> dailyRevList = new ArrayList<>();
            for (int i = 0; i < daysInMonth; i++) {
                dayLabels.add(i + 1);
                dailyRevList.add(Math.round(dailyRev[i] * 100.0) / 100.0);
            }

            // --- top 5 products by qty this month ---
            // M4d (slice 96): aggregate by productId and resolve names from catalog (≤5 lookups) — no reverse map, no Item load.
            // Grouped, ordered and limited to 5 in SQL — the database returns five rows, not a month of them.
            java.util.List<Object[]> topRows = sellRepo.topProductsByUpdated(startOfMonth, endOfMonth,
                    user.getOrganizationId(), userId, org.springframework.data.domain.PageRequest.of(0, 5));
            List<Map.Entry<Long, Double>> topEntries = new ArrayList<>();
            for (Object[] r : topRows) {
                if (r == null || r.length < 2 || r[0] == null) continue;
                topEntries.add(new java.util.AbstractMap.SimpleEntry<>(
                        ((Number) r[0]).longValue(), r[1] == null ? 0.0 : ((Number) r[1]).doubleValue()));
            }
            java.util.List<Long> topProductIds = topEntries.stream().map(Map.Entry::getKey).collect(Collectors.toList());
            java.util.Map<Long, com.myplus.commerce.contracts.dto.ProductRef> topProductById;
            try {
                topProductById = topProductIds.isEmpty() ? java.util.Collections.emptyMap()
                    : catalogClient.getProducts(topProductIds).stream()
                        .collect(Collectors.toMap(com.myplus.commerce.contracts.dto.ProductRef::getId, p -> p, (a, b) -> a));
            } catch (Exception ex) { topProductById = java.util.Collections.emptyMap(); }
            List<String> topItemNames = new ArrayList<>();
            List<Double> topItemQtys = new ArrayList<>();
            for (Map.Entry<Long, Double> entry : topEntries) {
                com.myplus.commerce.contracts.dto.ProductRef p = topProductById.get(entry.getKey());
                String name = (p != null && p.getName() != null) ? p.getName() : "Product #" + entry.getKey();
                topItemNames.add(name);
                topItemQtys.add(entry.getValue());
            }

            // --- sales by customer this month ---
            List<CustomerHistory> custHistories = customerHistoryRepo.findByUserIdAndDateRange(userId, startOfMonth, endOfMonth);
            Map<String, Double> salesByCustMap = new LinkedHashMap<>();
            for (CustomerHistory ch : custHistories) {
                String custName = (ch.getCustomer() != null && ch.getCustomer().getName() != null)
                    ? ch.getCustomer().getName() : "Walk-in";
                double amount = (ch.getPaidAmount() != null ? ch.getPaidAmount().doubleValue() : 0d)
                              + (ch.getDueAmount()  != null ? ch.getDueAmount().doubleValue()  : 0d);
                salesByCustMap.merge(custName, amount, Double::sum);
            }
            List<Map.Entry<String, Double>> topCustSales = salesByCustMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(8)
                .collect(Collectors.toList());
            List<String> custSalesNames = new ArrayList<>();
            List<Double> custSalesAmounts = new ArrayList<>();
            for (Map.Entry<String, Double> e : topCustSales) {
                custSalesNames.add(e.getKey());
                custSalesAmounts.add(Math.round(e.getValue() * 100.0) / 100.0);
            }

            // --- top customers with outstanding dues --- (org-scoped, was userId-only Example probe)
            // Filtered, sorted and limited in SQL. This used to load every customer of the tenant — 441 rows
            // hydrated to keep ten — and the shape worsens as a tenant grows rather than improving.
            List<Customer> allCustomers = customerRepo.findTopDueScoped(user.getOrganizationId(), userId,
                    org.springframework.data.domain.PageRequest.of(0, 10));
            List<Map<String, Object>> dueCustomers = allCustomers.stream()
                .map(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name",    c.getName());
                    row.put("contact", c.getContact() != null ? c.getContact() : "");
                    row.put("due",     c.getDueAmount());
                    row.put("dueDate", c.getDueDate() != null ? c.getDueDate().toString() : "");
                    return row;
                })
                .collect(Collectors.toList());

            Map<String, Object> chartData = new LinkedHashMap<>();
            chartData.put("monthLabels", monthLabels);
            chartData.put("monthRevenue", new ArrayList<>(revenueByMonth.values()));
            chartData.put("monthSalesCount", new ArrayList<>(salesByMonth.values()));
            chartData.put("dayLabels", dayLabels);
            chartData.put("dailyRevenue", dailyRevList);
            chartData.put("topItemNames", topItemNames);
            chartData.put("topItemQtys", topItemQtys);
            chartData.put("custSalesNames", custSalesNames);
            chartData.put("custSalesAmounts", custSalesAmounts);
            chartData.put("dueCustomers", dueCustomers);

            return new GenericResponse("SUCCESS", "chartData", chartData);
        } catch (Exception e) {
            LOGGER.error("getDashboardChartData error: " + e.getMessage(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }
}
