package com.myplus.marketplace.service;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.dto.CheckoutDTO;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.entity.Cart;
import com.myplus.marketplace.entity.CartItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Checkout (slice 69 E5 + slice 72 E13 coupons). Totals are computed server-side from the persistent cart (slice 68):
 * subtotal from the cart's snapshotted prices, EXCLUSIVE tax from each line's snapshotted rate, a server-priced
 * shipping fee, and an optional coupon discount off the subtotal. {@link #place} builds a trustworthy OrderDTO and
 * delegates to the existing {@link OrderService#placePublic} reserve→charge→confirm saga. Client money is ignored.
 */
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CartService cartService;
    private final OrderService orderService;
    private final CouponService couponService;

    /** Live totals for the current cart + chosen shipping method + optional coupon — no order is placed. */
    @Transactional(readOnly = true)
    public CheckoutDTO.Quote quote(Long org, String cartToken, String shippingMethod, String couponCode) {
        if (org == null) throw new ValidationException("Store (organizationId) is required");
        ShippingOption option = ShippingOption.from(shippingMethod);
        Cart cart = cartService.activeCart(org, cartToken).orElse(null);
        Totals t = totals(cart, option);
        CouponService.CouponResult cr = couponService.validateAndCompute(org, couponCode, t.subtotal);
        return assemble(t, option, cr);
    }

    /** Place the order from the server cart. Validates cart + address, applies a coupon, then delegates to the saga. */
    @Transactional
    public OrderDTO place(CheckoutDTO.Request req) {
        if (req.getOrganizationId() == null) throw new ValidationException("Store (organizationId) is required");
        ShippingOption option = ShippingOption.from(req.getShippingMethod());

        Cart cart = cartService.activeCart(req.getOrganizationId(), req.getCartToken()).orElse(null);
        if (cart == null || cart.getItems().isEmpty()) throw new ValidationException("Your cart is empty");
        if (option.requiresAddress() && isBlank(req.getShippingAddress()))
            throw new ValidationException("A delivery address is required for " + option.name() + " shipping");

        Totals t = totals(cart, option);
        CouponService.CouponResult cr = couponService.validateAndCompute(req.getOrganizationId(), req.getCouponCode(), t.subtotal);
        BigDecimal discount = cr.discount();
        BigDecimal total = t.subtotal.subtract(discount).add(t.taxTotal).add(t.shippingFee);
        boolean applied = discount.signum() > 0;

        OrderDTO dto = new OrderDTO();
        dto.setOrganizationId(req.getOrganizationId());
        dto.setCustomerName(req.getCustomerName());
        dto.setCustomerContact(req.getCustomerContact());
        dto.setShippingAddress(req.getShippingAddress());
        dto.setPaymentMode(req.getPaymentMode());
        dto.setCardToken(req.getCardToken());
        dto.setCustomerToken(req.getCustomerToken());
        dto.setCartToken(req.getCartToken());          // placePublic closes this cart on success
        dto.setShippingMethod(option.name());
        dto.setSubTotal(t.subtotal);
        dto.setTaxTotal(t.taxTotal);
        dto.setShippingFee(t.shippingFee);
        dto.setDiscountAmount(discount);
        dto.setCouponCode(applied ? cr.code() : null);
        dto.setTotal(total);                           // authoritative grand total — the charge uses this
        dto.setItems(toOrderLines(cart));              // server-sourced items + prices

        OrderDTO placed = orderService.placePublic(dto);
        if (applied) couponService.recordUse(cr.couponId());   // count the redemption only when it actually applied
        return placed;
    }

    // --- internals ---------------------------------------------------------------------------------------------

    private record Totals(List<CheckoutDTO.Line> lines, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal shippingFee) {}

    private Totals totals(Cart cart, ShippingOption option) {
        List<CheckoutDTO.Line> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        if (cart != null) {
            for (CartItem it : cart.getItems()) {
                BigDecimal unit = nz(it.getUnitPrice());
                int qty = it.getQuantity() != null ? it.getQuantity() : 0;
                BigDecimal net = unit.multiply(BigDecimal.valueOf(qty)).setScale(SCALE, RoundingMode.HALF_UP);
                BigDecimal rate = nz(it.getTaxRate());
                BigDecimal lineTax = net.multiply(rate).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
                subtotal = subtotal.add(net);
                taxTotal = taxTotal.add(lineTax);
                lines.add(CheckoutDTO.Line.builder()
                        .productId(it.getProductId()).name(it.getProductName())
                        .unitPrice(unit).quantity(qty).lineTotal(net).lineTax(lineTax).build());
            }
        }
        return new Totals(lines, subtotal, taxTotal, option.fee().setScale(SCALE, RoundingMode.HALF_UP));
    }

    private CheckoutDTO.Quote assemble(Totals t, ShippingOption option, CouponService.CouponResult cr) {
        BigDecimal discount = cr.discount();
        BigDecimal total = t.subtotal.subtract(discount).add(t.taxTotal).add(t.shippingFee);
        return CheckoutDTO.Quote.builder()
                .items(t.lines).subtotal(t.subtotal).discount(discount)
                .taxTotal(t.taxTotal).shippingFee(t.shippingFee).total(total)
                .shippingMethod(option.name()).couponCode(cr.code()).couponMessage(cr.message())
                .addressRequired(option.requiresAddress())
                .build();
    }

    private List<OrderDTO.Line> toOrderLines(Cart cart) {
        List<OrderDTO.Line> lines = new ArrayList<>();
        for (CartItem it : cart.getItems()) {
            OrderDTO.Line l = new OrderDTO.Line();
            l.setProductId(it.getProductId());
            l.setQuantity(it.getQuantity());
            l.setPrice(nz(it.getUnitPrice()));
            lines.add(l);
        }
        return lines;
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
