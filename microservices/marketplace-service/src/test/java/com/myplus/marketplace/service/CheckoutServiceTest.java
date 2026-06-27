package com.myplus.marketplace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.dto.CheckoutDTO;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.entity.Cart;
import com.myplus.marketplace.entity.CartItem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Slice 69, E5 — pure Mockito (always runs). Server-authoritative totals: subtotal from the cart, EXCLUSIVE tax per
 * line, server-priced shipping; place() builds the OrderDTO from the cart and delegates to the saga.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    private static final Long ORG = 1L;

    @Mock private CartService cartService;
    @Mock private OrderService orderService;
    @InjectMocks private CheckoutService service;

    private CartItem item(Long id, String price, String rate, int qty) {
        return CartItem.builder().productId(id).productName("P" + id)
                .unitPrice(new BigDecimal(price)).taxRate(new BigDecimal(rate)).quantity(qty).build();
    }

    /** A cart with: P10 @10.00 ×2 @5% tax (net 20, tax 1.00) + P20 @4.00 ×1 @0% (net 4, tax 0). */
    private Cart sampleCart() {
        return Cart.builder().organizationId(ORG).cartToken("t").status("ACTIVE")
                .items(new java.util.ArrayList<>(List.of(item(10L, "10.00", "5", 2), item(20L, "4.00", "0", 1))))
                .build();
    }

    private CheckoutDTO.Request req(String method, String address, String name) {
        return CheckoutDTO.Request.builder().organizationId(ORG).cartToken("t")
                .shippingMethod(method).shippingAddress(address).customerName(name).paymentMode("COD").build();
    }

    @Test
    void quote_computes_subtotal_tax_and_standard_shipping() {
        when(cartService.activeCart(ORG, "t")).thenReturn(Optional.of(sampleCart()));

        CheckoutDTO.Quote q = service.quote(ORG, "t", "STANDARD");

        assertThat(q.getSubtotal()).isEqualByComparingTo("24.00");   // 20 + 4
        assertThat(q.getTaxTotal()).isEqualByComparingTo("1.00");    // 1.00 + 0
        assertThat(q.getShippingFee()).isEqualByComparingTo("5.00"); // STANDARD
        assertThat(q.getTotal()).isEqualByComparingTo("30.00");      // 24 + 1 + 5
        assertThat(q.getItems()).hasSize(2);
        assertThat(q.isAddressRequired()).isTrue();
    }

    @Test
    void quote_pickup_is_free_and_needs_no_address() {
        when(cartService.activeCart(ORG, "t")).thenReturn(Optional.of(sampleCart()));

        CheckoutDTO.Quote q = service.quote(ORG, "t", "PICKUP");

        assertThat(q.getShippingFee()).isEqualByComparingTo("0.00");
        assertThat(q.getTotal()).isEqualByComparingTo("25.00");      // 24 + 1 + 0
        assertThat(q.isAddressRequired()).isFalse();
    }

    @Test
    void place_requires_an_address_for_delivery() {
        when(cartService.activeCart(ORG, "t")).thenReturn(Optional.of(sampleCart()));

        assertThatThrownBy(() -> service.place(req("STANDARD", "  ", "Buyer")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void place_rejects_an_empty_cart() {
        when(cartService.activeCart(ORG, "t")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.place(req("PICKUP", null, "Buyer")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void place_builds_a_server_priced_order_and_delegates_to_the_saga() {
        when(cartService.activeCart(ORG, "t")).thenReturn(Optional.of(sampleCart()));
        when(orderService.placePublic(any(OrderDTO.class))).thenAnswer(i -> i.getArgument(0));

        service.place(req("PICKUP", null, "Buyer"));   // pickup → no address required

        ArgumentCaptor<OrderDTO> sent = ArgumentCaptor.forClass(OrderDTO.class);
        org.mockito.Mockito.verify(orderService).placePublic(sent.capture());
        OrderDTO dto = sent.getValue();
        assertThat(dto.getItems()).hasSize(2);                       // sourced from the cart
        assertThat(dto.getSubTotal()).isEqualByComparingTo("24.00");
        assertThat(dto.getTaxTotal()).isEqualByComparingTo("1.00");
        assertThat(dto.getShippingFee()).isEqualByComparingTo("0.00");
        assertThat(dto.getTotal()).isEqualByComparingTo("25.00");    // authoritative grand total
        assertThat(dto.getShippingMethod()).isEqualTo("PICKUP");
        assertThat(dto.getCartToken()).isEqualTo("t");               // so the saga closes the cart
    }
}
