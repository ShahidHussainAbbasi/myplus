package com.myplus.inventory.service;

import com.myplus.inventory.dto.ProductDTO;
import com.myplus.inventory.entity.Category;
import com.myplus.inventory.entity.Product;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.DuplicateResourceException;
import com.myplus.common.web.exception.ResourceNotFoundException;
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
        return productRepository.findScoped(CurrentUser.organizationId(), CurrentUser.userId(), pageable).map(this::toDto);
    }

    public ProductDTO getById(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public ProductDTO create(ProductDTO dto) {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();
        if (productRepository.existsBySkuScoped(dto.getSku(), orgId, userId)) {
            throw new DuplicateResourceException("Product SKU already exists: " + dto.getSku());
        }
        Product p = fromDto(dto, new Product());
        stampTenant(p);
        return toDto(productRepository.save(p));
    }

    @Transactional
    public ProductDTO update(Long id, ProductDTO dto) {
        Product p = getEntity(id);   // scoped — anti-IDOR
        if (dto.getSku() != null && !dto.getSku().equals(p.getSku())
                && productRepository.existsBySkuScoped(dto.getSku(), CurrentUser.organizationId(), CurrentUser.userId())) {
            throw new DuplicateResourceException("Product SKU already exists: " + dto.getSku());
        }
        fromDto(dto, p);
        return toDto(productRepository.save(p));
    }

    @Transactional
    public void delete(Long id) {
        productRepository.delete(getEntity(id));   // scoped — anti-IDOR
    }

    public Page<ProductDTO> search(String q, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        return productRepository.searchScoped(q, categoryId, minPrice, maxPrice,
                CurrentUser.organizationId(), CurrentUser.userId(), pageable).map(this::toDto);
    }

    public Page<ProductDTO> getByCategory(Long categoryId, Pageable pageable) {
        return productRepository.findByCategoryScoped(categoryId, CurrentUser.organizationId(), CurrentUser.userId(), pageable).map(this::toDto);
    }

    public List<ProductDTO> getLowStock() {
        return productRepository.findLowStockScoped(CurrentUser.organizationId(), CurrentUser.userId()).stream().map(this::toDto).toList();
    }

    public List<ProductDTO> getOutOfStock() {
        return productRepository.findOutOfStockScoped(CurrentUser.organizationId(), CurrentUser.userId()).stream().map(this::toDto).toList();
    }

    public List<ProductDTO> getExpiring(int days) {
        LocalDate today = LocalDate.now();
        return productRepository.findExpiringScoped(today, today.plusDays(days),
                CurrentUser.organizationId(), CurrentUser.userId()).stream().map(this::toDto).toList();
    }

    @Transactional
    public ProductDTO setActive(Long id, boolean active) {
        Product p = getEntity(id);   // scoped — anti-IDOR
        p.setIsActive(active);
        return toDto(productRepository.save(p));
    }

    /** Scoped lookup so one tenant can never read/mutate another's product by id (anti-IDOR). */
    public Product getEntity(Long id) {
        return productRepository.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    /** Stamp the active tenant + actor on a new row (gateway-propagated identity). */
    private void stampTenant(Product p) {
        AuthenticatedUser u = CurrentUser.get().orElse(null);
        if (u != null) {
            p.setOrganizationId(u.getOrganizationId());
            p.setUserId(u.getUserId());
            if (p.getCreatedBy() == null) p.setCreatedBy(u.getUserId());
        }
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
