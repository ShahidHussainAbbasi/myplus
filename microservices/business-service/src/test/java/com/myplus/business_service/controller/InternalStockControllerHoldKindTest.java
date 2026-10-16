package com.myplus.business_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.commerce.contracts.dto.StockHoldRequest;
import com.myplus.commerce.contracts.dto.StockReservationLine;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.web.exception.ValidationException;

/** MKT-5b — the hold seam passes the caller's kind: ORDER by default, CONSIGNMENT on request, never CHECKOUT. */
class InternalStockControllerHoldKindTest {

    static final long ORG = 6L;

    InventoryClient inventory = mock(InventoryClient.class);
    RequestUtil requestUtil = mock(RequestUtil.class);
    InternalStockController controller = new InternalStockController();

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(controller, "inventoryClient", inventory);
        ReflectionTestUtils.setField(controller, "requestUtil", requestUtil);
        AuthenticatedUser user = mock(AuthenticatedUser.class);
        when(user.getOrganizationId()).thenReturn(ORG);
        when(requestUtil.getCurrentUser()).thenReturn(user);
        StockReservationResponse ok = new StockReservationResponse();
        ok.setStatus(ReservationStatus.RESERVED);
        when(inventory.reserve(any())).thenReturn(ok);
    }

    static StockHoldRequest hold(StockReservationRequest.HoldKind kind) {
        return StockHoldRequest.builder().organizationId(ORG).holdKey("MKT-ST-000005-0-consign")
                .lines(List.of(new StockReservationLine(61L, BigDecimal.valueOf(2)))).holdKind(kind).build();
    }

    StockReservationRequest.HoldKind sent() {
        ArgumentCaptor<StockReservationRequest> req = ArgumentCaptor.forClass(StockReservationRequest.class);
        verify(inventory).reserve(req.capture());
        return req.getValue().getHoldKind();
    }

    @Test
    @DisplayName("no kind is an ORDER hold, as every caller before MKT-5b expects")
    void defaultIsOrder() {
        assertThat(controller.hold(hold(null)).getBody().isHeld()).isTrue();
        assertThat(sent()).isEqualTo(StockReservationRequest.HoldKind.ORDER);
    }

    @Test
    @DisplayName("a consignment hold reaches inventory as CONSIGNMENT")
    void consignment() {
        controller.hold(hold(StockReservationRequest.HoldKind.CONSIGNMENT));
        assertThat(sent()).isEqualTo(StockReservationRequest.HoldKind.CONSIGNMENT);
    }

    @Test
    @DisplayName("a CHECKOUT hold is refused here: a till's hold is the sale's own")
    void checkoutRefused() {
        assertThatThrownBy(() -> controller.hold(hold(StockReservationRequest.HoldKind.CHECKOUT)))
                .isInstanceOf(ValidationException.class);
        verify(inventory, never()).reserve(any());
    }
}
