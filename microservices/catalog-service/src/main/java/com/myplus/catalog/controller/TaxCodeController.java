package com.myplus.catalog.controller;

import com.myplus.catalog.dto.TaxCodeDTO;
import com.myplus.catalog.service.TaxCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Multi-rate tax: per-org tax-code master. Reads are open to any authenticated user (the product form needs the list
 * for its dropdown); mutations are ADMIN-gated (tax policy, mirrors business-service {@code saveTaxSetting}).
 */
@RestController
@RequestMapping("/api/catalog/tax-codes")
@RequiredArgsConstructor
public class TaxCodeController {

    private final TaxCodeService taxCodeService;

    @GetMapping
    public List<TaxCodeDTO> list() {
        return taxCodeService.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public TaxCodeDTO create(@RequestBody TaxCodeDTO dto) {
        return taxCodeService.create(dto);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public TaxCodeDTO update(@PathVariable Long id, @RequestBody TaxCodeDTO dto) {
        return taxCodeService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public void delete(@PathVariable Long id) {
        taxCodeService.delete(id);
    }
}
