package com.myplus.marketplace.multiseller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductIdentityKeyTest {

    @Test
    @DisplayName("[MKT-R6.2] the source's own example key is produced exactly")
    void sourceExample() {
        assertThat(ProductIdentityKey.general("Samsung", "Galaxy A32", "128GB", "Black", null, null, null, "New",
                "Warranty 12M")).isEqualTo("SAMSUNG|GALAXY-A32|128GB|BLACK|NEW|WARRANTY-12M");
    }

    @Test
    @DisplayName("[MKT-R6.6] 64GB and 128GB never share a key")
    void storageNeverMerges() {
        String a = ProductIdentityKey.general("Samsung", "A32", "64GB", "Black", null, null, null, "New", null);
        String b = ProductIdentityKey.general("Samsung", "A32", "128GB", "Black", null, null, null, "New", null);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("[MKT-R6.6] [MKT-R6.3] Panadol 10 and 20 tablets never share a key")
    void packSizeNeverMerges() {
        String ten = ProductIdentityKey.medicine("GSK", "Panadol Extra", "500 mg", "Tablet", "10");
        String twenty = ProductIdentityKey.medicine("GSK", "Panadol Extra", "500 mg", "Tablet", "20");
        assertThat(ten).isEqualTo("GSK|PANADOL-EXTRA|500MG|TABLET|10").isNotEqualTo(twenty);
    }

    @Test
    @DisplayName("[MKT-R6.1] the same name with different identity attributes is NOT the same product")
    void nameAloneNeverMatches() {
        String black = ProductIdentityKey.general("Samsung", "A32", "128GB", "Black", null, null, null, "New", null);
        String used = ProductIdentityKey.general("Samsung", "A32", "128GB", "Black", null, null, null, "Used", null);
        assertThat(black).isNotEqualTo(used);
    }

    @Test
    @DisplayName("[MKT-R6.2] spacing, case and accents do not split one product into two")
    void normalisationIsConservative() {
        assertThat(ProductIdentityKey.general(" samsung ", "galaxy  a32", "128 gb", "BLACK", null, null, null, "new", null))
                .isEqualTo(ProductIdentityKey.general("Samsung", "Galaxy A32", "128GB", "Black", null, null, null, "New", null));
        assertThat(ProductIdentityKey.normalize("Café")).isEqualTo("CAFE");
        // synonyms are NOT merged: a missed match is caught in review, a wrong one is sold
        assertThat(ProductIdentityKey.normalize("Blk")).isNotEqualTo(ProductIdentityKey.normalize("Black"));
    }

    @Test
    @DisplayName("[MKT-R6.2] a separator inside a value cannot forge a different key")
    void separatorInjection() {
        String forged = ProductIdentityKey.general("Samsung|A32", "128GB", null, null, null, null, null, null, null);
        assertThat(forged).isEqualTo("SAMSUNG-A32|128GB");
    }

    @Test
    @DisplayName("[MKT-R6.2] a valid GTIN wins; a malformed barcode is ignored, never trusted")
    void gtin() {
        assertThat(ProductIdentityKey.preferGtin("4006381333931", "K")).isEqualTo("GTIN:4006381333931");
        assertThat(ProductIdentityKey.preferGtin("4006381333932", "K")).isEqualTo("K");   // bad check digit
        assertThat(ProductIdentityKey.preferGtin("12345", "K")).isEqualTo("K");
        assertThat(ProductIdentityKey.preferGtin(null, "K")).isEqualTo("K");
        assertThat(ProductIdentityKey.isValidGtin("96385074")).isTrue();                   // GTIN-8
    }

    @Test
    @DisplayName("[MKT-R6.3] a medicine key with a missing strength is refused, not guessed")
    void incompleteMedicine() {
        assertThatThrownBy(() -> ProductIdentityKey.medicine("GSK", "Panadol", " ", "Tablet", "10"))
                .isInstanceOf(MarketplaceRuleException.class)
                .hasMessageContaining("strength");
    }
}
