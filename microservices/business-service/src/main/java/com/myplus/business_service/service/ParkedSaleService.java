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
    private final com.myplus.business_service.util.RequestUtil requestUtil;   // multi-location: the active store

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
                .storeId(requestUtil.activeStoreId())      // a hold is resumed at the till it was parked at
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

    /**
     * PARK-CLAIM-1 — resume = TAKE the parked sale off the shelf, in one step, exactly once.
     *
     * <p>Resume used to be a read ({@link #resume}) followed by a SEPARATE client call to {@code /deleteParked}.
     * That call needs DELETE_PRIVILEGE, which a USER-role cashier does not hold, and the till made it silently —
     * so for an ordinary cashier the parked sale was never removed. It stayed in the list, could be resumed and
     * completed again under a fresh idempotency key, and became a second invoice for the same goods.
     *
     * <p>The cart is parsed BEFORE anything is deleted, so an unreadable row is never destroyed. The scoped
     * DELETE's affected-row count is the arbiter: a second claim of the same id — another tab, a double click —
     * deletes nothing and is told the sale is gone.
     *
     * <p>Accepted trade-off (user ruling, 2026-09-24): if the response is lost after this commits, the basket must
     * be re-rung. A lost basket is recoverable at the counter; a duplicate sale is money taken twice.
     *
     * @throws ResourceNotFoundException not this cashier's, or already resumed/discarded
     */
    @Transactional
    public JsonNode claim(Long id, Long orgId, Long userId) {
        ParkedSale p = parkedSaleRepo.findByIdAndOrganizationIdAndUserId(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Parked sale not found"));
        JsonNode cart;
        try {
            cart = objectMapper.readTree(p.getCartJson() == null ? "{}" : p.getCartJson());
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the parked cart", e);   // row KEPT
        }
        if (parkedSaleRepo.deleteScoped(id, orgId, userId) != 1) {
            throw new ResourceNotFoundException("Parked sale already resumed");
        }
        return cart;
    }

    @Transactional
    public void discard(Long id, Long orgId, Long userId) {
        ParkedSale p = parkedSaleRepo.findByIdAndOrganizationIdAndUserId(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Parked sale not found"));
        parkedSaleRepo.delete(p);
    }
}
