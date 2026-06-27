package com.myplus.catalog.service;

import com.myplus.catalog.dto.ProductDTO;
import com.myplus.catalog.entity.Category;
import com.myplus.catalog.entity.Product;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.DuplicateResourceException;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    // readOnly tx keeps the session open through toDto()'s lazy category access (open-in-view is false) —
    // otherwise listing a product that HAS a category throws "Could not initialize proxy [Category] - no session".
    @Transactional(readOnly = true)
    public Page<ProductDTO> getAll(Pageable pageable) {
        return productRepository.findScoped(CurrentUser.organizationId(), CurrentUser.userId(), pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
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
        p.setOrganizationId(orgId);
        p.setUserId(userId);
        if (p.getCreatedBy() == null) p.setCreatedBy(userId);
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

    @Transactional(readOnly = true)
    public Page<ProductDTO> search(String q, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        return productRepository.searchScoped(q, categoryId, minPrice, maxPrice,
                CurrentUser.organizationId(), CurrentUser.userId(), pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Page<ProductDTO> getByCategory(Long categoryId, Pageable pageable) {
        return productRepository.findByCategoryScoped(categoryId, CurrentUser.organizationId(), CurrentUser.userId(), pageable).map(this::toDto);
    }

    @Transactional
    public ProductDTO setActive(Long id, boolean active) {
        Product p = getEntity(id);   // scoped — anti-IDOR
        p.setIsActive(active);
        return toDto(productRepository.save(p));
    }

    /** Scoped lookup — anti-IDOR. */
    public Product getEntity(Long id) {
        return productRepository.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    /** Lightweight cross-service reference (+ price) for the sell saga (slice 33, U3b). */
    public com.myplus.commerce.contracts.dto.ProductRef getRef(Long id) {
        Product p = getEntity(id);   // scoped — 404 if not this tenant's
        return new com.myplus.commerce.contracts.dto.ProductRef(
                p.getId(), p.getSku(), p.getName(), p.getUnit(), p.getSellingPrice(), p.getTaxRate());
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
                .manufacturer(p.getManufacturer())
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
            Category cat = categoryRepository.findByIdScoped(dto.getCategoryId(), CurrentUser.organizationId(), CurrentUser.userId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + dto.getCategoryId()));
            p.setCategory(cat);
        }
        p.setUnit(dto.getUnit());
        p.setManufacturer(dto.getManufacturer());
        p.setSellingPrice(dto.getSellingPrice());
        p.setTaxRate(dto.getTaxRate());
        if (dto.getIsActive() != null) p.setIsActive(dto.getIsActive());
        p.setImageUrl(dto.getImageUrl());
        if (dto.getCreatedBy() != null) p.setCreatedBy(dto.getCreatedBy());
        return p;
    }
}
