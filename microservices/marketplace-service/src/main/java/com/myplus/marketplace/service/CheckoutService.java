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
 * Checkout (slice 69, E5). Totals are computed server-side from the persistent cart (slice 68): subtotal from the
 * cart's snapshotted prices, EXCLUSIVE tax from each line's snapshotted rate (mirrors G3's {@code net × rate ÷ 100}),
 * and a server-priced shipping fee. {@link #place} builds a trustworthy OrderDTO and delegates to the existing
 * {@link OrderService#placePublic} reserve→charge→confirm saga (which also closes the cart). Client money is ignored.
 */
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CartService cartService;
    private final OrderService orderService;

    /** Live totals for the current cart + chosen shipping method — no order is placed. */
    @Transactional(readOnly = true)
    public CheckoutDTO.Quote quote(Long org, String cartToken, String shippingMethod) {
        if (org == null) throw new ValidationException("Store (organizationId) is required");
        ShippingOption option = ShippingOption.from(shippingMethod);
        Cart cart = cartService.activeCart(org, cartToken).orElse(null);
        return build(cart, option);
    }

    /** Place the order from the server cart. Validates the cart + address, then delegates to the saga. */
    @Transactional
    public OrderDTO place(CheckoutDTO.Request req) {
        if (req.getOrganizationId() == null) throw new ValidationException("Store (organizationId) is required");
        ShippingOption option = ShippingOption.from(req.getShippingMethod());

        Cart cart = cartService.activeCart(req.getOrganizationId(), req.getCartToken()).orElse(null);
        if (cart == null || cart.getItems().isEmpty()) throw new ValidationException("Your cart is empty");
        if (option.requiresAddress() && isBlank(req.getShippingAddress()))
            throw new ValidationException("A delivery address is required for " + option.name() + " shipping");

        CheckoutDTO.Quote q = build(cart, option);

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
        dto.setSubTotal(q.getSubtotal());
        dto.setTaxTotal(q.getTaxTotal());
        dto.setShippingFee(q.getShippingFee());
        dto.setTotal(q.getTotal());                    // authoritative grand total — the charge uses this
        dto.setItems(toOrderLines(cart));              // server-sourced items + prices

        return orderService.placePublic(dto);
    }

    // --- internals ---------------------------------------------------------------------------------------------

    private CheckoutDTO.Quote build(Cart cart, ShippingOption option) {
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
        BigDecimal shipping = option.fee().setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(taxTotal).add(shipping);
        return CheckoutDTO.Quote.builder()
                .items(lines).subtotal(subtotal).taxTotal(taxTotal).shippingFee(shipping).total(total)
                .shippingMethod(option.name()).addressRequired(option.requiresAddress())
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
