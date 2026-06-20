package com.myplus.inventory.service;

import com.myplus.inventory.entity.Product;
import com.myplus.inventory.entity.StockAlert;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.inventory.repository.ProductRepository;
import com.myplus.inventory.repository.StockAlertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AlertService {

    private final StockAlertRepository alertRepository;
    private final ProductRepository productRepository;

    @Scheduled(fixedDelay = 3600000) // hourly
    @Transactional
    public void checkAndCreateAlerts() {
        List<Product> lowStock = productRepository.findLowStockProducts();
        for (Product p : lowStock) {
            StockAlert alert = StockAlert.builder()
                    .product(p)
                    .alertType(p.getCurrentStock() <= 0
                            ? StockAlert.AlertType.OUT_OF_STOCK
                            : StockAlert.AlertType.LOW_STOCK)
                    .message("Product " + p.getName() + " is " +
                            (p.getCurrentStock() <= 0 ? "out of stock" : "low on stock: " + p.getCurrentStock()))
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
