package com.myplus.catalog.controller;

import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Public storefront product browse (slice 47) — anonymous (gateway allow-lists {@code /api/catalog/public/};
 * SecurityConfig permits it). The store is identified by {@code org} since there is no JWT identity. Returns a
 * minimal projection (no cost/internal fields).
 */
@RestController
@RequestMapping("/api/catalog/public")
@RequiredArgsConstructor
public class PublicProductController {

    private final ProductRepository productRepository;

    @GetMapping("/products")
    public ApiResponse<List<Map<String, Object>>> products(@RequestParam("org") Long org,
            @RequestParam(value = "q", required = false) String q) {
        // slice 60: optional name search (case-insensitive contains); blank q = full list.
        List<com.myplus.catalog.entity.Product> products = (q == null || q.isBlank())
                ? productRepository.findByOrganizationIdAndIsActiveTrueOrderByNameAsc(org)
                : productRepository.findByOrganizationIdAndIsActiveTrueAndNameContainingIgnoreCaseOrderByNameAsc(org, q.trim());
        return ApiResponse.success(products.stream().map(this::storefrontView).toList());
    }

    private Map<String, Object> storefrontView(Product p) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("sku", p.getSku());
        m.put("unit", p.getUnit());
        m.put("description", p.getDescription());
        m.put("sellingPrice", p.getSellingPrice());
        m.put("taxRate", p.getTaxRate());
        m.put("imageUrl", p.getImageUrl());
        return m;
    }
}
