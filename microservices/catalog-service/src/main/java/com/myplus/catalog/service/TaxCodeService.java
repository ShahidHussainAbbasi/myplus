package com.myplus.catalog.service;

import com.myplus.catalog.dto.TaxCodeDTO;
import com.myplus.catalog.entity.TaxCode;
import com.myplus.catalog.repository.TaxCodeRepository;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-rate tax: manages the per-org tax-code master and resolves a product's rate from its assigned code. Pure
 * policy — no pricing here (the sale saga still reads the resolved rate from {@code ProductRef}). Tenant-scoped
 * (org + NULL-fallback), anti-IDOR, with a single-default-per-org invariant enforced on upsert.
 */
@Service
@RequiredArgsConstructor
public class TaxCodeService {

    private final TaxCodeRepository repo;

    @Transactional(readOnly = true)
    public List<TaxCodeDTO> list() {
        return repo.findScoped(CurrentUser.organizationId(), CurrentUser.userId()).stream().map(this::toDto).toList();
    }

    @Transactional
    public TaxCodeDTO create(TaxCodeDTO dto) {
        TaxCode t = new TaxCode();
        t.setOrganizationId(CurrentUser.organizationId());
        t.setUserId(CurrentUser.userId());
        apply(t, dto);
        return toDto(repo.save(t));
    }

    @Transactional
    public TaxCodeDTO update(Long id, TaxCodeDTO dto) {
        TaxCode t = repo.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Tax code not found: " + id));
        apply(t, dto);
        return toDto(repo.save(t));
    }

    @Transactional
    public void delete(Long id) {
        TaxCode t = repo.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Tax code not found: " + id));
        repo.delete(t);   // products keep their tax_code_id; a dangling id falls back to product rate / org default
    }

    /** All of this org's code rates by id — resolved once so the sale-read path never does a per-product lookup. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> ratesByOrg(Long organizationId) {
        Map<Long, BigDecimal> m = new HashMap<>();
        for (TaxCode t : repo.findByOrganizationId(organizationId))
            m.put(t.getId(), t.getRate() != null ? t.getRate() : BigDecimal.ZERO);
        return m;
    }

    private void apply(TaxCode t, TaxCodeDTO dto) {
        if (dto.getName() != null && !dto.getName().isBlank()) t.setName(dto.getName().trim());
        t.setRate(dto.getRate() != null && dto.getRate().signum() >= 0 ? dto.getRate() : BigDecimal.ZERO);
        t.setActive(dto.getActive() == null || Boolean.TRUE.equals(dto.getActive()));
        boolean makeDefault = Boolean.TRUE.equals(dto.getIsDefault());
        t.setIsDefault(makeDefault);
        // Single default per org: assigning a new default clears any other (flush the flag off the siblings).
        if (makeDefault) {
            for (TaxCode other : repo.findByOrganizationId(CurrentUser.organizationId())) {
                if (!other.getId().equals(t.getId()) && Boolean.TRUE.equals(other.getIsDefault())) {
                    other.setIsDefault(false);
                    repo.save(other);
                }
            }
        }
    }

    private TaxCodeDTO toDto(TaxCode t) {
        return TaxCodeDTO.builder()
                .id(t.getId()).name(t.getName()).rate(t.getRate())
                .isDefault(Boolean.TRUE.equals(t.getIsDefault()))
                .active(Boolean.TRUE.equals(t.getActive()))
                .build();
    }
}
