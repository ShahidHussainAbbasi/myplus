package com.myplus.catalog.controller;

import com.myplus.catalog.dto.PriceRuleDTO;
import com.myplus.commerce.contracts.dto.PriceQuote;
import com.myplus.catalog.service.PriceRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Contract & tiered pricing (slice b2b-P2 = OMS B1 = requirement #10).
 *
 * <p>The QUOTE is open to any authenticated user, because every till needs it on every sale — and it is not a
 * disclosure: it answers only for the caller's own tenant and returns prices the cashier is about to charge
 * anyway. MANAGING rules is ADMIN-gated: a negotiated rate is commercial policy, not a counter decision.
 */
@RestController
@RequestMapping("/api/catalog/price-rules")
@RequiredArgsConstructor
public class PriceRuleController {

    private final PriceRuleService priceRuleService;

    /**
     * "What does this buyer pay for these lines?" — called ONCE per sale by business-service, never per line.
     * A client sends ids and quantities only; it never sends a price and is never believed about one.
     */
    @PostMapping("/quote")
    public PriceQuote quote(@RequestBody PriceQuote request) {
        return priceRuleService.quote(request);
    }

    @GetMapping
    public List<PriceRuleDTO> list() {
        return priceRuleService.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public PriceRuleDTO create(@RequestBody PriceRuleDTO dto) {
        return priceRuleService.create(dto);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public PriceRuleDTO update(@PathVariable Long id, @RequestBody PriceRuleDTO dto) {
        return priceRuleService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public void delete(@PathVariable Long id) {
        priceRuleService.delete(id);
    }
}
