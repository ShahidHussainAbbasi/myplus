package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.myplus.catalog.repository.ProductRepository;
import com.myplus.common.docnum.DocumentNumberService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** SKU-AUTO — the 12-digit automatic SKU: format, skipping a hand-typed number, and the legacy no-org row. */
@ExtendWith(MockitoExtension.class)
class SkuAllocatorTest {

    @Mock private DocumentNumberService documentNumbers;
    @Mock private ProductRepository productRepository;
    @Mock private com.myplus.catalog.repository.ProductBarcodeRepository stickers;
    @InjectMocks private SkuAllocator allocator;

    @Test
    @DisplayName("12 digits: '2' + the 11-digit counter value")
    void format() {
        assertThat(SkuAllocator.format(1)).isEqualTo("200000000001").hasSize(12);
        assertThat(SkuAllocator.format(12345)).isEqualTo("200000012345");
    }

    @Test
    @DisplayName("the next free number — one a user already typed is skipped")
    void skips_a_taken_code() {
        when(documentNumbers.next(7L, "SKU")).thenReturn(1L, 2L);
        when(productRepository.existsCodeScoped("200000000001", 7L, 3L)).thenReturn(true);
        when(productRepository.existsCodeScoped("200000000002", 7L, 3L)).thenReturn(false);
        assertThat(allocator.next(7L, 3L)).isEqualTo("200000000002");
    }

    @Test
    @DisplayName("a number already used as a sticker is skipped too - at the till a sticker wins over a SKU")
    void skips_a_sticker_code() {
        when(documentNumbers.next(7L, "SKU")).thenReturn(1L, 2L);
        when(productRepository.existsCodeScoped(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(3L))).thenReturn(false);
        when(stickers.findByOrganizationIdAndBarcode(7L, "200000000001"))
                .thenReturn(java.util.Optional.of(new com.myplus.catalog.entity.ProductBarcode()));
        when(stickers.findByOrganizationIdAndBarcode(7L, "200000000002")).thenReturn(java.util.Optional.empty());
        assertThat(allocator.next(7L, 3L)).isEqualTo("200000000002");
    }

    @Test
    @DisplayName("a legacy row with no organisation gets no automatic code (the counter is per business)")
    void no_org_no_code() {
        assertThat(allocator.next(null, 3L)).isNull();
        verifyNoInteractions(documentNumbers, productRepository, stickers);
    }

    @Test
    @DisplayName("a run of hand-typed numbers that never ends is refused loudly, not looped forever")
    void guard() {
        when(documentNumbers.next(7L, "SKU")).thenAnswer(i -> 5L);
        when(productRepository.existsCodeScoped(anyString(), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(3L))).thenReturn(true);
        assertThatThrownBy(() -> allocator.next(7L, 3L)).isInstanceOf(IllegalStateException.class);
    }
}
