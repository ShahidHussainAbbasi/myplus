package com.myplus.marketplace.multiseller.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Regulated;
import com.myplus.marketplace.multiseller.domain.PhaseGuard.CheckoutLine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PhaseGuardTest {

    private final PhaseGuard phase1 = new PhaseGuard(1, true);

    @Test
    @DisplayName("[MKT-R17.1] [MKT-R20.1] two lines from one seller check out together")
    void oneSeller() {
        assertThatCode(() -> phase1.checkCheckout(List.of(
                new CheckoutLine(1, 101, StockSourceType.MERCHANT, Regulated.NONE),
                new CheckoutLine(2, 101, StockSourceType.MERCHANT, Regulated.NONE)))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[MKT-R17.1] [MKT-R20.2] two sellers in one Phase 1 checkout are refused on the server")
    void twoSellersRefused() {
        assertThatThrownBy(() -> phase1.checkCheckout(List.of(
                new CheckoutLine(1, 101, StockSourceType.MERCHANT, Regulated.NONE),
                new CheckoutLine(2, 102, StockSourceType.MERCHANT, Regulated.NONE))))
                .isInstanceOf(MarketplaceRuleException.class)
                .hasMessage("Items from different sellers must be checked out separately.");
    }

    @Test
    @DisplayName("[MKT-R17.2] Phase 2 allows a multi-seller checkout (the guard is the only thing that changes)")
    void phase2AllowsMultiSeller() {
        assertThatCode(() -> new PhaseGuard(2, true).checkCheckout(List.of(
                new CheckoutLine(1, 101, StockSourceType.MERCHANT, Regulated.NONE),
                new CheckoutLine(2, 102, StockSourceType.MERCHANT, Regulated.NONE)))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[MKT-R20.2] [MKT-R1.2] dropship and consignment are refused until their phase")
    void sourcesByPhase() {
        assertThatThrownBy(() -> phase1.checkOffer(StockSourceType.SUPPLIER, Regulated.NONE))
                .hasMessageContaining("supplier");
        assertThatThrownBy(() -> new PhaseGuard(4, true).checkOffer(StockSourceType.CONSIGNMENT, Regulated.NONE))
                .isInstanceOf(MarketplaceRuleException.class);
        assertThatCode(() -> new PhaseGuard(5, true).checkOffer(StockSourceType.CONSIGNMENT, Regulated.NONE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[MKT-R20.2] prescription medicines are refused in Phase 1")
    void regulatedRefused() {
        assertThatThrownBy(() -> phase1.checkOffer(StockSourceType.MERCHANT, Regulated.PRESCRIPTION))
                .extracting(e -> ((MarketplaceRuleException) e).code()).isEqualTo("REGULATED_BLOCKED");
    }

    @Test
    @DisplayName("[MKT-R17.1] an empty checkout is refused")
    void empty() {
        assertThatThrownBy(() -> phase1.checkCheckout(List.of())).hasMessage("Your basket is empty.");
    }

    @Test
    @DisplayName("[MKT-R4.2] [MKT-R20.4] platform stock is sellable once a warehouse is named, without lifting Phase 1's other limits")
    void platformStock() {
        assertThatThrownBy(() -> phase1.checkOffer(StockSourceType.PLATFORM, Regulated.NONE)).hasMessageContaining("platform");
        assertThatCode(() -> phase1.checkOffer(StockSourceType.PLATFORM, Regulated.NONE, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> phase1.checkOffer(StockSourceType.SUPPLIER, Regulated.NONE, true)).hasMessageContaining("supplier");
        assertThatThrownBy(() -> phase1.checkOffer(StockSourceType.PLATFORM, Regulated.PRESCRIPTION, true))
                .extracting(e -> ((MarketplaceRuleException) e).code()).isEqualTo("REGULATED_BLOCKED");
        // the multi-seller switch stays the operator's: the warehouse and a shop in one basket are two sellers
        assertThatThrownBy(() -> phase1.checkCheckout(List.of(
                new CheckoutLine(1, 101, StockSourceType.PLATFORM, Regulated.NONE),
                new CheckoutLine(2, 102, StockSourceType.MERCHANT, Regulated.NONE)), false, true))
                .hasMessage("Items from different sellers must be checked out separately.");
        assertThatCode(() -> phase1.checkCheckout(List.of(new CheckoutLine(1, 101, StockSourceType.PLATFORM, Regulated.NONE)),
                false, true)).doesNotThrowAnyException();
    }
}
