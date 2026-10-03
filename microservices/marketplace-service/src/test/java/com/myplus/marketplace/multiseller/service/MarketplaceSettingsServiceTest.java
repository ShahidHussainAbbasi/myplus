package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.OfferSort;
import com.myplus.marketplace.multiseller.entity.MarketplacePlatformSetting;
import com.myplus.marketplace.multiseller.repository.MarketplacePlatformSettingRepository;

/** MKT-1d — the operator's default order for the public catalogue (source §7.4 "ranking defaults"). */
@ExtendWith(MockitoExtension.class)
class MarketplaceSettingsServiceTest {

    @Mock MarketplacePlatformSettingRepository rows;
    @Mock SellerAccess access;
    @InjectMocks MarketplaceSettingsService service;

    @Test
    @DisplayName("[MKT-R7.4] the operator saves a default; input is normalised and who/when are stamped")
    void save() {
        when(rows.findById(MarketplacePlatformSetting.DEFAULT_SORT)).thenReturn(Optional.empty());
        when(access.userId()).thenReturn(7L);
        assertThat(service.setDefaultSort("  fastest ")).isEqualTo("FASTEST");
        ArgumentCaptor<MarketplacePlatformSetting> saved = ArgumentCaptor.forClass(MarketplacePlatformSetting.class);
        verify(rows).save(saved.capture());
        assertThat(saved.getValue().getSettingValue()).isEqualTo("FASTEST");
        assertThat(saved.getValue().getUpdatedByUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("[MKT-R7.4] only the sorts with data behind them can be the default; nothing is saved otherwise")
    void onlyBackedSorts() {
        for (String bad : new String[] {"NEAREST", "QUALITY", "PROMOTION", "", null, "LOWEST_PRICE; DROP"}) {
            assertThatThrownBy(() -> service.setDefaultSort(bad)).as(String.valueOf(bad)).isInstanceOf(ValidationException.class);
        }
        verify(rows, never()).save(any());
    }

    @Test
    @DisplayName("[MKT-R22.1] a tenant can neither read nor set the platform default (operator only)")
    void operatorOnly() {
        doThrow(new AccessDeniedException("Access denied")).when(access).assertOperator();
        assertThatThrownBy(() -> service.setDefaultSort("FASTEST")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.operatorDefaultSort()).isInstanceOf(AccessDeniedException.class);
        verify(rows, never()).save(any());
    }

    @Test
    @DisplayName("[MKT-R18.4] resolve: the customer's sort wins; blank or unknown → the default; RECOMMENDED → the chain")
    void resolve() {
        MarketplacePlatformSetting d = new MarketplacePlatformSetting();
        d.setSettingValue("WARRANTY");
        when(rows.findById(MarketplacePlatformSetting.DEFAULT_SORT)).thenReturn(Optional.of(d));
        assertThat(service.resolve("lowest_price")).isEqualTo(OfferSort.LOWEST_PRICE);
        assertThat(service.resolve(null)).isEqualTo(OfferSort.WARRANTY);
        assertThat(service.resolve("<script>")).isEqualTo(OfferSort.WARRANTY);
        assertThat(service.resolve("recommended")).isNull();
    }
}
