package com.myplus.inventory.service;

import com.myplus.inventory.entity.StockAlert;
import com.myplus.inventory.entity.StockLevel;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.inventory.repository.StockAlertRepository;
import com.myplus.inventory.repository.StockLevelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AlertService {

    private final StockAlertRepository alertRepository;
    private final StockLevelRepository stockLevelRepository;

    @Scheduled(fixedDelay = 3600000) // hourly
    @Transactional
    public void checkAndCreateAlerts() {
        // Scheduled job: no security context -> deliberately cross-tenant scan of all stock levels.
        List<StockLevel> lowStock = stockLevelRepository.findLowStock();
        for (StockLevel sl : lowStock) {
            boolean out = sl.getCurrentStock() != null && sl.getCurrentStock() <= 0;
            StockAlert alert = StockAlert.builder()
                    .productId(sl.getProductId())
                    .alertType(out ? StockAlert.AlertType.OUT_OF_STOCK : StockAlert.AlertType.LOW_STOCK)
                    .message("Product #" + sl.getProductId() + " is "
                            + (out ? "out of stock" : "low on stock: " + sl.getCurrentStock()))
                    .build();
            alertRepository.save(alert);
        }
    }

    public List<StockAlert> getUnreadAlerts() {
        return alertRepository.findByIsReadFalse();
    }

    public List<StockAlert> getByProduct(Long productId) {
        return alertRepository.findByProductId(productId);
    }

    @Transactional
    public StockAlert markRead(Long alertId) {
        StockAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Alert not found"));
        alert.setIsRead(true);
        return alertRepository.save(alert);
    }

    @Transactional
    public int markAllRead() {
        return alertRepository.markAllRead();
    }
}
