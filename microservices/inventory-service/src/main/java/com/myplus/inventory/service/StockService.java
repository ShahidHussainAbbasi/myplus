package com.myplus.inventory.service;

import com.myplus.inventory.dto.StockDTOs.*;
import com.myplus.inventory.entity.*;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.inventory.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class StockService {

    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockEntryRepository stockEntryRepository;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final StockTransferRepository stockTransferRepository;

    @Transactional
    public StockEntry addStock(StockEntryDTO dto) {
        Product product = productRepository.findById(dto.getProductId())
                .orElseThrow(() -> new ValidationException("Product not found"));
        Warehouse warehouse = dto.getWarehouseId() != null
                ? warehouseRepository.findById(dto.getWarehouseId()).orElse(null) : null;

        StockEntry entry = StockEntry.builder()
                .product(product)
                .warehouse(warehouse)
                .quantity(dto.getQuantity())
                .batchNo(dto.getBatchNo())
                .lotNo(dto.getLotNo())
                .expiryDate(dto.getExpiryDate())
                .purchasePrice(dto.getPurchasePrice())
                .supplierId(dto.getSupplierId())
                .notes(dto.getNotes())
                .build();

        product.setCurrentStock((product.getCurrentStock() != null ? product.getCurrentStock() : 0f) + dto.getQuantity());
        productRepository.save(product);
        return stockEntryRepository.save(entry);
    }

    @Transactional
    public StockAdjustment adjustStock(StockAdjustmentDTO dto) {
        Product product = productRepository.findById(dto.getProductId())
                .orElseThrow(() -> new ValidationException("Product not found"));
        Warehouse warehouse = dto.getWarehouseId() != null
                ? warehouseRepository.findById(dto.getWarehouseId()).orElse(null) : null;

        float current = product.getCurrentStock() != null ? product.getCurrentStock() : 0f;
        switch (dto.getAdjustmentType()) {
            case INCREASE -> product.setCurrentStock(current + dto.getQuantity());
            case DECREASE -> {
                if (current < dto.getQuantity()) throw new ValidationException("Insufficient stock");
                product.setCurrentStock(current - dto.getQuantity());
            }
            case TRANSFER -> {
                // Transfer handled via StockTransfer
            }
        }
        productRepository.save(product);

        StockAdjustment adj = StockAdjustment.builder()
                .product(product)
                .warehouse(warehouse)
                .adjustmentType(dto.getAdjustmentType())
                .quantity(dto.getQuantity())
                .reason(dto.getReason())
                .adjustedBy(dto.getAdjustedBy())
                .notes(dto.getNotes())
                .build();
        return stockAdjustmentRepository.save(adj);
    }

    @Transactional
    public StockTransfer transferStock(StockTransferDTO dto) {
        Product product = productRepository.findById(dto.getProductId())
                .orElseThrow(() -> new ValidationException("Product not found"));
        Warehouse from = warehouseRepository.findById(dto.getFromWarehouseId())
                .orElseThrow(() -> new ValidationException("Source warehouse not found"));
        Warehouse to = warehouseRepository.findById(dto.getToWarehouseId())
                .orElseThrow(() -> new ValidationException("Destination warehouse not found"));

        StockTransfer transfer = StockTransfer.builder()
                .product(product)
                .fromWarehouse(from)
                .toWarehouse(to)
                .quantity(dto.getQuantity())
                .transferredBy(dto.getTransferredBy())
                .status(StockTransfer.TransferStatus.COMPLETED)
                .notes(dto.getNotes())
                .build();
        return stockTransferRepository.save(transfer);
    }

    public Float getCurrentStock(Long productId) {
        return productRepository.findById(productId).map(Product::getCurrentStock).orElse(0f);
    }

    public Page<StockEntry> getHistory(Long productId, Pageable pageable) {
        return stockEntryRepository.findByProductId(productId, pageable);
    }

    public StockSummaryDTO getSummary() {
        long totalProducts = productRepository.count();
        long lowStockCount = productRepository.findLowStockProducts().size();
        long outOfStockCount = productRepository.findOutOfStockProducts().size();
        BigDecimal totalValue = productRepository.findAll().stream()
                .filter(p -> p.getCurrentStock() != null && p.getCostPrice() != null)
                .map(p -> p.getCostPrice().multiply(BigDecimal.valueOf(p.getCurrentStock())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return StockSummaryDTO.builder()
                .totalProducts(totalProducts)
                .lowStockCount(lowStockCount)
                .outOfStockCount(outOfStockCount)
                .totalInventoryValue(totalValue)
                .build();
    }
}
