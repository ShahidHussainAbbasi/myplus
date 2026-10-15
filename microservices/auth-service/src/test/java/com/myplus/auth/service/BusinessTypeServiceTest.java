package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.myplus.auth.entity.BusinessType;
import com.myplus.auth.entity.ProductLine;
import com.myplus.auth.exception.ValidationException;
import com.myplus.auth.repository.BusinessTypeRepository;
import com.myplus.auth.repository.ProductLineRepository;

/** HC-1a — a new tenant is created as an ACTIVE business type row, and the picker lists the rows by product line. */
class BusinessTypeServiceTest {

    private final ProductLineRepository lines = mock(ProductLineRepository.class);
    private final BusinessTypeRepository types = mock(BusinessTypeRepository.class);
    private final BusinessTypeService svc = new BusinessTypeService(lines, types);

    private static BusinessType type(String key, String line, String orgType, String shape, String status) {
        BusinessType t = new BusinessType();
        t.setTypeKey(key); t.setLineKey(line); t.setNameKey("ui.js.bt." + key); t.setName(key);
        t.setOrgType(orgType); t.setShape(shape); t.setStatus(status);
        return t;
    }

    @Test
    void the_hospital_clinic_row_gives_the_clinic_org_type_and_shape() {
        when(types.findById("hospital_clinic")).thenReturn(Optional.of(type("hospital_clinic", "HEALTHCARE", "CLINIC", "clinic", "ACTIVE")));
        BusinessType t = svc.forProvisioning(" hospital_clinic ");
        assertThat(t.getOrgType()).isEqualTo("CLINIC");
        assertThat(t.getShape()).isEqualTo("clinic");
    }

    @Test
    void an_unknown_blank_or_inactive_type_is_refused_never_guessed() {
        when(types.findById("school")).thenReturn(Optional.of(type("school", "EDUCATION", "EDUCATION", "general", "INACTIVE")));
        when(types.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.forProvisioning("school")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> svc.forProvisioning("nope")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> svc.forProvisioning(" ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> svc.forProvisioning(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_catalogue_groups_types_under_their_line_and_flags_inactive_ones() {
        ProductLine hc = new ProductLine();
        hc.setLineKey("HEALTHCARE"); hc.setNameKey("ui.js.pl.healthcare"); hc.setName("Healthcare"); hc.setStatus("ACTIVE");
        when(lines.findAllByOrderBySortOrderAsc()).thenReturn(List.of(hc));
        when(types.findAllByOrderByLineKeyAscSortOrderAsc()).thenReturn(List.of(
                type("hospital_clinic", "HEALTHCARE", "CLINIC", "clinic", "ACTIVE"),
                type("pharmacy", "HEALTHCARE", "PHARMA", "pharmacy", "ACTIVE"),
                type("retail", "BUSINESS", "BUSINESS", "retail", "ACTIVE")));
        List<Map<String, Object>> out = svc.catalogue();
        assertThat(out).hasSize(1);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get(0).get("types");
        assertThat(rows).extracting(r -> r.get("key")).containsExactly("hospital_clinic", "pharmacy");
        assertThat(rows.get(1).get("orgType")).isEqualTo("PHARMA");
        assertThat(rows.get(0).get("active")).isEqualTo(true);
    }
}
