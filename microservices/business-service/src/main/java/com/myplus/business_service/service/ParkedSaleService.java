package com.myplus.business_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplus.business_service.dto.ParkSaleDTO;
import com.myplus.business_service.dto.ParkedSaleSummaryDTO;
import com.myplus.business_service.entity.ParkedSale;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.business_service.repository.ParkedSaleRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Park / hold & resume (POS R10, slice 40). Stores the cashier's in-progress cart as JSON to resume later; org +
 * cashier scoped (anti-IDOR). No stock/invoice effect — completion happens via the normal addSell on resume.
 */
@Service
@RequiredArgsConstructor
public class ParkedSaleService {

    private final ParkedSaleRepo parkedSaleRepo;
    private final ObjectMapper objectMapper;

    @Transactional
    public ParkedSale park(ParkSaleDTO dto, Long orgId, Long userId) {
        String cartJson;
        try {
            cartJson = dto.getCart() == null ? "{}" : objectMapper.writeValueAsString(dto.getCart());
        } catch (Exception e) {
            throw new RuntimeException("Could not serialize the cart to park", e);
        }
        return parkedSaleRepo.save(ParkedSale.builder()
                .organizationId(orgId).userId(userId)
                .label(dto.getLabel() != null && !dto.getLabel().isBlank() ? dto.getLabel().trim() : "Parked sale")
                .itemCount(dto.getItemCount())
                .total(dto.getTotal() != null ? dto.getTotal() : BigDecimal.ZERO)
                .cartJson(cartJson)
                .build());
    }

    public List<ParkedSaleSummaryDTO> list(Long orgId, Long userId) {
        return parkedSaleRepo.findByOrganizationIdAndUserIdOrderByParkedAtDesc(orgId, userId).stream()
                .map(p -> new ParkedSaleSummaryDTO(p.getId(), p.getLabel(), p.getItemCount(), p.getTotal(), p.getParkedAt()))
                .collect(Collectors.toList());
    }

    /** The stored cart for a parked sale (scoped) — parsed back to JSON for the client to rebuild the cart. */
    public JsonNode resume(Long id, Long orgId, Long userId) {
        ParkedSale p = parkedSaleRepo.findByIdAndOrganizationIdAndUserId(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Parked sale not found"));
        try {
            return objectMapper.readTree(p.getCartJson() == null ? "{}" : p.getCartJson());
        } catch (Exception e) {
            throw new RuntimeException("Could not read the parked cart", e);
        }
    }

    @Transactional
    public void discard(Long id, Long orgId, Long userId) {
        ParkedSale p = parkedSaleRepo.findByIdAndOrganizationIdAndUserId(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Parked sale not found"));
        parkedSaleRepo.delete(p);
    }
}
