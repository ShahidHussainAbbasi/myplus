package com.myplus.pharma.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.pharma.dto.PrescriptionDTO;
import com.myplus.pharma.dto.PrescriptionItemDTO;

/** HMS H1 (L-1) — a doctor's prescription names only medicines in this pharmacy's catalogue, checked live. */
class PrescribedProductCheckTest {

    CatalogClient catalog;
    PrescribedProductCheck check;

    @BeforeEach
    void setUp() {
        catalog = mock(CatalogClient.class);
        check = new PrescribedProductCheck(catalog);
    }

    private static PrescriptionDTO rx(Long... productIds) {
        PrescriptionDTO d = new PrescriptionDTO();
        for (Long id : productIds) {
            PrescriptionItemDTO it = new PrescriptionItemDTO();
            it.setProductId(id);
            it.setMedicineName("Med " + id);
            it.setQuantity(1);
            d.getItems().add(it);
        }
        return d;
    }

    @Test
    void every_medicine_in_the_list_passes_and_the_read_is_live() {
        when(catalog.getProductsFresh(anyList(), eq(true)))
                .thenReturn(List.of(ProductRef.builder().id(1L).build(), ProductRef.builder().id(2L).build()));
        assertThatCode(() -> check.assertInCatalogue(rx(1L, 2L))).doesNotThrowAnyException();
        verify(catalog).getProductsFresh(List.of(1L, 2L), true);   // fresh: never a cached row for a safety decision
    }

    @Test
    void a_medicine_this_pharmacy_does_not_have_is_named() {
        // 987654321: no product of this business (another business's id is equally absent — the read is scoped)
        when(catalog.getProductsFresh(anyList(), eq(true))).thenReturn(List.of(ProductRef.builder().id(1L).build()));
        assertThatThrownBy(() -> check.assertInCatalogue(rx(1L, 987654321L)))
                .hasMessage("'Med 987654321' is not in this pharmacy's list. Choose it again from the list.");
    }

    @Test
    void if_the_catalogue_cannot_be_asked_the_submit_is_refused_in_words() {
        when(catalog.getProductsFresh(anyList(), eq(true))).thenThrow(new RuntimeException("catalog-service down"));
        assertThatThrownBy(() -> check.assertInCatalogue(rx(1L)))
                .hasMessage("The pharmacy's medicine list could not be checked. Press Submit again.");
    }
}
