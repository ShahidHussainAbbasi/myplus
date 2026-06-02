package com.myplus.business_service.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.business_service.security.AuthenticatedUser;
import com.myplus.business_service.entity.Company;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Item;
import com.myplus.business_service.entity.Sell;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.service.ICompanyService;
import com.myplus.business_service.service.ICustomerService;
import com.myplus.business_service.service.IItemService;
import com.myplus.business_service.service.ISellService;
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
    private ICompanyService companyService;

    @Autowired
    private VenderRepo venderRepo;

    @Autowired
    private ICustomerService customerService;

    @Autowired
    private IItemService itemService;

    @Autowired
    private ISellService sellService;

    @Autowired
    private CustomerHistoryRepo customerHistoryRepo;

    @GetMapping("/getBusinessDashboardStats")
    @ResponseBody
    public GenericResponse getBusinessDashboardStats(HttpServletRequest request) {
        try {
            AuthenticatedUser user = requestUtil.getCurrentUser();
            Long userId = user.getUserId();

            Company companyFilter = new Company();
            companyFilter.setUserId(userId);
            long companyCount = companyService.count(Example.of(companyFilter));

            Vender venderFilter = new Vender();
            venderFilter.setUserId(userId);
            long venderCount = venderRepo.count(Example.of(venderFilter));

            Customer customerFilter = new Customer();
            customerFilter.setUserId(userId);
            long customerCount = customerService.count(Example.of(customerFilter));

            Item itemFilter = new Item();
            itemFilter.setUserId(userId);
            long itemCount = itemService.count(Example.of(itemFilter));

            LocalDateTime startOfMonth = appUtil.firstDateTimeOfMonth();
            LocalDateTime endOfMonth = appUtil.lastDateTimeOfMonth();
            List<Sell> monthlySells = sellService.findSellByDates(startOfMonth, endOfMonth, userId);
            long sellCount = monthlySells.size();
            double monthlyRevenue = monthlySells.stream()
                .mapToDouble(s -> s.getNetAmount() != null ? s.getNetAmount().doubleValue() : 0.0)
                .sum();

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
            List<Sell> trendSells = sellService.findSellByDates(sixMonthsAgoStart, appUtil.lastDateTimeOfMonth(), userId);
            for (Sell s : trendSells) {
                if (s.getUpdated() != null) {
                    String key = s.getUpdated().format(monthKey);
                    if (revenueByMonth.containsKey(key)) {
                        revenueByMonth.merge(key, s.getTotalAmount() != null ? s.getTotalAmount().doubleValue() : 0.0, Double::sum);
                        salesByMonth.merge(key, 1, Integer::sum);
                    }
                }
            }

            // --- daily revenue this month ---
            LocalDateTime startOfMonth = appUtil.firstDateTimeOfMonth();
            LocalDateTime endOfMonth = appUtil.lastDateTimeOfMonth();
            List<Sell> monthlySells = sellService.findSellByDates(startOfMonth, endOfMonth, userId);
            int daysInMonth = now.toLocalDate().lengthOfMonth();
            double[] dailyRev = new double[daysInMonth];
            for (Sell s : monthlySells) {
                if (s.getUpdated() != null) {
                    int d = s.getUpdated().getDayOfMonth() - 1;
                    dailyRev[d] += s.getTotalAmount() != null ? s.getTotalAmount().doubleValue() : 0.0;
                }
            }
            List<Integer> dayLabels = new ArrayList<>();
            List<Double> dailyRevList = new ArrayList<>();
            for (int i = 0; i < daysInMonth; i++) {
                dayLabels.add(i + 1);
                dailyRevList.add(Math.round(dailyRev[i] * 100.0) / 100.0);
            }

            // --- top 5 items by qty this month ---
            Map<Long, Double> itemQtyMap = new HashMap<>();
            for (Sell s : monthlySells) {
                if (s.getStock() != null && s.getStock().getItemId() != null && s.getQuantity() != null) {
                    itemQtyMap.merge(s.getStock().getItemId(), s.getQuantity().doubleValue(), Double::sum);
                }
            }
            List<Map.Entry<Long, Double>> topEntries = itemQtyMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(5)
                .collect(Collectors.toList());
            List<String> topItemNames = new ArrayList<>();
            List<Double> topItemQtys = new ArrayList<>();
            for (Map.Entry<Long, Double> entry : topEntries) {
                Optional<Item> itemOpt = itemService.findById(entry.getKey());
                String name = itemOpt.isPresent() && itemOpt.get().getIname() != null
                    ? itemOpt.get().getIname() : "Item #" + entry.getKey();
                topItemNames.add(name);
                topItemQtys.add(entry.getValue());
            }

            // --- sales by customer this month ---
            List<CustomerHistory> custHistories = customerHistoryRepo.findByUserIdAndDateRange(userId, startOfMonth, endOfMonth);
            Map<String, Double> salesByCustMap = new LinkedHashMap<>();
            for (CustomerHistory ch : custHistories) {
                String custName = (ch.getCustomer() != null && ch.getCustomer().getName() != null)
                    ? ch.getCustomer().getName() : "Walk-in";
                double amount = (ch.getPaidAmount() != null ? ch.getPaidAmount() : 0f)
                              + (ch.getDueAmount()  != null ? ch.getDueAmount()  : 0f);
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

            // --- top customers with outstanding dues ---
            Customer dueFilter = new Customer();
            dueFilter.setUserId(userId);
            List<Customer> allCustomers = customerService.findAll(Example.of(dueFilter));
            List<Map<String, Object>> dueCustomers = allCustomers.stream()
                .filter(c -> c.getDueAmount() != null && c.getDueAmount() > 0)
                .sorted((a, b) -> Float.compare(
                    b.getDueAmount() != null ? b.getDueAmount() : 0f,
                    a.getDueAmount() != null ? a.getDueAmount() : 0f))
                .limit(10)
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
