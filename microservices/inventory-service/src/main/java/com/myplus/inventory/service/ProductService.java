package com.myplus.inventory.service;

import com.myplus.inventory.dto.ProductDTO;
import com.myplus.inventory.entity.Category;
import com.myplus.inventory.entity.Product;
import com.myplus.inventory.exception.DuplicateResourceException;
import com.myplus.inventory.exception.ResourceNotFoundException;
import com.myplus.inventory.repository.CategoryRepository;
import com.myplus.inventory.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public Page<ProductDTO> getAll(Pageable pageable) {
        return productRepository.findAll(pageable).map(this::toDto);
    }

    public ProductDTO getById(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public ProductDTO create(ProductDTO dto) {
        if (productRepository.existsBySku(dto.getSku())) {
            throw new DuplicateResourceException("Product SKU already exists: " + dto.getSku());
        }
        Product p = fromDto(dto, new Product());
        return toDto(productRepository.save(p));
    }

    @Transactional
    public ProductDTO update(Long id, ProductDTO dto) {
        Product p = getEntity(id);
        if (dto.getSku() != null && !dto.getSku().equals(p.getSku()) && productRepository.existsBySku(dto.getSku())) {
            throw new DuplicateResourceException("Product SKU already exists: " + dto.getSku());
        }
        fromDto(dto, p);
        return toDto(productRepository.save(p));
    }

    @Transactional
    public void delete(Long id) {
        productRepository.delete(getEntity(id));
    }

    public Page<ProductDTO> search(String q, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        return productRepository.search(q, categoryId, minPrice, maxPrice, pageable).map(this::toDto);
    }

    public Page<ProductDTO> getByCategory(Long categoryId, Pageable pageable) {
        return productRepository.findByCategoryId(categoryId, pageable).map(this::toDto);
    }

    public List<ProductDTO> getLowStock() {
        return productRepository.findLowStockProducts().stream().map(this::toDto).toList();
    }

    public List<ProductDTO> getOutOfStock() {
        return productRepository.findOutOfStockProducts().stream().map(this::toDto).toList();
    }

    public List<ProductDTO> getExpiring(int days) {
        LocalDate today = LocalDate.now();
        return productRepository.findExpiringProducts(today, today.plusDays(days)).stream().map(this::toDto).toList();
    }

    @Transactional
    public ProductDTO setActive(Long id, boolean active) {
        Product p = getEntity(id);
        p.setIsActive(active);
        return toDto(productRepository.save(p));
    }

    public Product getEntity(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    public ProductDTO toDto(Product p) {
        return ProductDTO.builder()
                .id(p.getId())
                .sku(p.getSku())
                .name(p.getName())
                .description(p.getDescription())
                .categoryId(p.getCategory() != null ? p.getCategory().getId() : null)
                .categoryName(p.getCategory() != null ? p.getCategory().getName() : null)
                .unit(p.getUnit())
                .minStockLevel(p.getMinStockLevel())
                .maxStockLevel(p.getMaxStockLevel())
                .reorderPoint(p.getReorderPoint())
                .currentStock(p.getCurrentStock())
                .costPrice(p.getCostPrice())
                .sellingPrice(p.getSellingPrice())
                .taxRate(p.getTaxRate())
                .isActive(p.getIsActive())
                .imageUrl(p.getImageUrl())
                .createdBy(p.getCreatedBy())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private Product fromDto(ProductDTO dto, Product p) {
        p.setSku(dto.getSku());
        p.setName(dto.getName());
        p.setDescription(dto.getDescription());
        if (dto.getCategoryId() != null) {
            Category cat = categoryRepository.findById(dto.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + dto.getCategoryId()));
            p.setCategory(cat);
        }
        p.setUnit(dto.getUnit());
        p.setMinStockLevel(dto.getMinStockLevel());
        p.setMaxStockLevel(dto.getMaxStockLevel());
        p.setReorderPoint(dto.getReorderPoint());
        if (dto.getCurrentStock() != null) p.setCurrentStock(dto.getCurrentStock());
        p.setCostPrice(dto.getCostPrice());
        p.setSellingPrice(dto.getSellingPrice());
        p.setTaxRate(dto.getTaxRate());
        if (dto.getIsActive() != null) p.setIsActive(dto.getIsActive());
        p.setImageUrl(dto.getImageUrl());
        if (dto.getCreatedBy() != null) p.setCreatedBy(dto.getCreatedBy());
        return p;
    }
}
