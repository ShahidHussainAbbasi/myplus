package com.myplus.marketplace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
 * Slice 69 (E5) + slice 72 (E13 coupons) — pure Mockito (always runs). Server-authoritative totals: subtotal from the
 * cart, EXCLUSIVE tax per line, server-priced shipping, optional coupon discount; place() builds the OrderDTO from the
 * cart and delegates to the saga.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    private static final Long ORG = 1L;

    @Mock private CartService cartService;
    @Mock private OrderService orderService;
    @Mock private CouponService couponService;
    @InjectMocks private CheckoutService service;

    private CartItem item(Long id, String price, String rate, int qty) {
        return CartItem.builder().productId(id).productName("P" + id)
                .unitPrice(new BigDecimal(price)).taxRate(new BigDecimal(rate)).quantity(qty).build();
    }

    /** A cart with: P10 @10.00 ×2 @5% tax (net 20, tax 1.00) + P20 @4.00 ×1 @0% (net 4, tax 0). subtotal 24, tax 1. */
    private Cart sampleCart() {
        return Cart.builder().organizationId(ORG).cartToken("t").status("ACTIVE")
                .items(new java.util.ArrayList<>(List.of(item(10L, "10.00", "5", 2), item(20L, "4.00", "0", 1))))
                .build();
    }

    private CheckoutDTO.Request req(String method, String address, String name) {
        return CheckoutDTO.Request.builder().organizationId(ORG).cartToken("t")
                .shippingMethod(method).shippingAddress(address).customerName(name).paymentMode("COD").build();
    }

    private CouponService.CouponResult noCoupon() {
        return new CouponService.CouponResult(BigDecimal.ZERO, null, null, null);
    }

    @Test
    void quote_computes_subtotal_tax_and_standard_shipping() {
        when(cartService.activeCart(ORG, "t")).thenReturn(Optional.of(sampleCart()));
        when(couponService.validateAndCompute(eq(ORG), any(), any())).thenReturn(noCoupon());

        CheckoutDTO.Quote q = service.quote(ORG, "t", "STANDARD", null);

        assertThat(q.getSubtotal()).isEqualByComparingTo("24.00");   // 20 + 4
        assertThat(q.getTaxTotal()).isEqualByComparingTo("1.00");    // 1.00 + 0
        assertThat(q.getShippingFee()).isEqualByComparingTo("5.00"); // STANDARD
        assertThat(q.getDiscount()).isEqualByComparingTo("0.00");
        assertThat(q.getTotal()).isEqualByComparingTo("30.00");      // 24 + 1 + 5
        assertThat(q.getItems()).hasSize(2);
        assertThat(q.isAddressRequired()).isTrue();
    }

    @Test
    void quote_pickup_is_free_and_needs_no_address() {
        when(cartService.activeCart(ORG, "t")).thenReturn(Optional.of(sampleCart()));
        when(couponService.validateAndCompute(eq(ORG), any(), any())).thenReturn(noCoupon());

        CheckoutDTO.Quote q = service.quote(ORG, "t", "PICKUP", null);

        assertThat(q.getShippingFee()).isEqualByComparingTo("0.00");
        assertThat(q.getTotal()).isEqualByComparingTo("25.00");      // 24 + 1 + 0
        assertThat(q.isAddressRequired()).isFalse();
    }

    @Test
    void quote_with_a_percent_coupon_reduces_the_total() {
        when(cartService.activeCart(ORG, "t")).thenReturn(Optional.of(sampleCart()));
        // SAVE10 → 10% off the 24.00 subtotal = 2.40
        when(couponService.validateAndCompute(ORG, "SAVE10", new BigDecimal("24.00")))
                .thenReturn(new CouponService.CouponResult(new BigDecimal("2.40"), "SAVE10", null, 9L));

        CheckoutDTO.Quote q = service.quote(ORG, "t", "STANDARD", "SAVE10");

        assertThat(q.getDiscount()).isEqualByComparingTo("2.40");
        assertThat(q.getCouponCode()).isEqualTo("SAVE10");
        assertThat(q.getTotal()).isEqualByComparingTo("27.60");      // 24 - 2.40 + 1 + 5
    }

    @Test
    void quote_with_an_invalid_coupon_applies_no_discount_but_carries_the_message() {
        when(cartService.activeCart(ORG, "t")).thenReturn(Optional.of(sampleCart()));
        when(couponService.validateAndCompute(ORG, "BAD", new BigDecimal("24.00")))
                .thenReturn(new CouponService.CouponResult(BigDecimal.ZERO, "BAD", "This coupon has expired", null));

        CheckoutDTO.Quote q = service.quote(ORG, "t", "STANDARD", "BAD");

        assertThat(q.getDiscount()).isEqualByComparingTo("0.00");
        assertThat(q.getCouponMessage()).isEqualTo("This coupon has expired");
        assertThat(q.getTotal()).isEqualByComparingTo("30.00");      // unchanged
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
        when(couponService.validateAndCompute(eq(ORG), any(), any())).thenReturn(noCoupon());
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

    @Test
    void place_applies_a_coupon_and_records_its_use() {
        when(cartService.activeCart(ORG, "t")).thenReturn(Optional.of(sampleCart()));
        when(couponService.validateAndCompute(ORG, "SAVE10", new BigDecimal("24.00")))
                .thenReturn(new CouponService.CouponResult(new BigDecimal("2.40"), "SAVE10", null, 9L));
        when(orderService.placePublic(any(OrderDTO.class))).thenAnswer(i -> i.getArgument(0));

        CheckoutDTO.Request r = CheckoutDTO.Request.builder().organizationId(ORG).cartToken("t")
                .shippingMethod("PICKUP").customerName("Buyer").paymentMode("COD").couponCode("SAVE10").build();
        service.place(r);

        ArgumentCaptor<OrderDTO> sent = ArgumentCaptor.forClass(OrderDTO.class);
        org.mockito.Mockito.verify(orderService).placePublic(sent.capture());
        OrderDTO dto = sent.getValue();
        assertThat(dto.getDiscountAmount()).isEqualByComparingTo("2.40");
        assertThat(dto.getCouponCode()).isEqualTo("SAVE10");
        assertThat(dto.getTotal()).isEqualByComparingTo("22.60");    // 24 - 2.40 + 1 + 0
        org.mockito.Mockito.verify(couponService).recordUse(9L);     // redemption counted
    }
}
