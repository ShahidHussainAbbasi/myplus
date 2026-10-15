package com.myplus.auth.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.auth.entity.BusinessType;
import com.myplus.auth.entity.ProductLine;
import com.myplus.auth.exception.ValidationException;
import com.myplus.auth.repository.BusinessTypeRepository;
import com.myplus.auth.repository.ProductLineRepository;

import lombok.RequiredArgsConstructor;

/**
 * HC-1a — the product line → business type picker, from the V35 rows (standards §1e F3: the list is data).
 *
 * <p>Two readers: the operator's New tenant form ({@link #catalogue}) and {@code AuthService.provisionTenant}
 * ({@link #forProvisioning}), which takes the tenant's org type and shape FROM THE ROW — so the screen sends one key
 * and cannot pair a clinic org type with a shop shape.
 */
@Service
@RequiredArgsConstructor
public class BusinessTypeService {

    private final ProductLineRepository productLines;
    private final BusinessTypeRepository businessTypes;

    /**
     * Every product line with its business types, in order. INACTIVE rows are included and flagged, so a screen
     * that NAMES an existing tenant's type (the console's row badge) can still find it; only ACTIVE ones are offered.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> catalogue() {
        List<BusinessType> types = businessTypes.findAllByOrderByLineKeyAscSortOrderAsc();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProductLine line : productLines.findAllByOrderBySortOrderAsc()) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("key", line.getLineKey());
            l.put("nameKey", line.getNameKey());
            l.put("name", line.getName());
            l.put("active", "ACTIVE".equalsIgnoreCase(line.getStatus()));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (BusinessType t : types) {
                if (!line.getLineKey().equals(t.getLineKey())) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("key", t.getTypeKey());
                r.put("nameKey", t.getNameKey());
                r.put("name", t.getName());
                r.put("orgType", t.getOrgType());
                r.put("shape", t.getShape());
                r.put("active", t.isActive());
                rows.add(r);
            }
            l.put("types", rows);
            out.add(l);
        }
        return out;
    }

    /** The ACTIVE business type a new tenant is created as; refused — never guessed — when unknown or not offered. */
    @Transactional(readOnly = true)
    public BusinessType forProvisioning(String typeKey) {
        String key = typeKey == null ? "" : typeKey.trim();
        BusinessType t = key.isEmpty() ? null : businessTypes.findById(key).orElse(null);
        if (t == null || !t.isActive()) {
            throw new ValidationException("Choose a business type for this tenant.");
        }
        return t;
    }
}
