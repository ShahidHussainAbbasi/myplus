package com.web.controller.business;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.persistence.Repo.business.VenderRepo;
import com.persistence.model.User;
import com.persistence.model.Company;
import com.persistence.model.business.Customer;
import com.persistence.model.business.Item;
import com.persistence.model.business.Sell;
import com.persistence.model.business.Vender;
import com.service.business.ICompanyService;
import com.service.business.ICustomerService;
import com.service.business.IItemService;
import com.service.business.ISellService;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

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

    @GetMapping("/getBusinessDashboardStats")
    @ResponseBody
    public GenericResponse getBusinessDashboardStats(HttpServletRequest request) {
        try {
            User user = requestUtil.getCurrentUser();
            Long userId = user.getId();

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
}
