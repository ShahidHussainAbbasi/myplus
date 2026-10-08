package com.myplus.marketplace.multiseller.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.marketplace.multiseller.dto.MarketplaceOrderDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceCustomer;
import com.myplus.marketplace.multiseller.service.MarketplaceAccountService;
import com.myplus.marketplace.multiseller.service.MarketplaceCheckoutService;
import com.myplus.marketplace.multiseller.service.MarketplaceCustomerService;
import com.myplus.marketplace.multiseller.service.MarketplaceShortageService;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1e2 — the marketplace customer's account. Public routes (the gateway's {@code /api/marketplace/public/}); the
 * customer is identified ONLY by {@code X-Mkt-Session}, which the monolith sets from an HttpOnly cookie — never by the
 * gateway's {@code X-User-*} identity, which belongs to staff and is trusted only with the internal secret.
 *
 * <pre>
 *   POST /public/mkt/account/register            {phone, name, password, email?} → {token, name}
 *   POST /public/mkt/account/login               {phone, password} → {token, name}
 *   POST /public/mkt/account/logout
 *   GET  /public/mkt/account/me
 *   GET  /public/mkt/account/orders?page=&amp;size=
 *   POST /public/mkt/account/claim               {orderNo, phone}
 *   POST /public/mkt/account/orders/{no}/cancel  {reason?}
 *   POST /public/mkt/account/checkout            the checkout, signed in (COD or CARD)
 * </pre>
 */
@RestController
@RequiredArgsConstructor
public class MarketplaceAccountController {

    static final String SESSION = "X-Mkt-Session";

    private final MarketplaceCustomerService customers;
    private final MarketplaceAccountService accounts;
    private final MarketplaceCheckoutService checkout;
    private final MarketplaceShortageService shortages;

    public record Register(String phone, String name, String password, String email) { }

    public record Login(String phone, String password) { }

    public record Claim(String orderNo, String phone) { }

    public record Cancel(String reason) { }

    @PostMapping("/public/mkt/account/register")
    public ApiResponse<MarketplaceCustomerService.Signed> register(@RequestBody Register b) {
        return ApiResponse.success(customers.register(b.phone(), b.name(), b.password(), b.email()), "Your account is ready.");
    }

    @PostMapping("/public/mkt/account/login")
    public ApiResponse<MarketplaceCustomerService.Signed> login(@RequestBody Login b) {
        return ApiResponse.success(customers.login(b.phone(), b.password()), "Signed in.");
    }

    @PostMapping("/public/mkt/account/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = SESSION, required = false) String token) {
        customers.logout(token);
        return ApiResponse.success(null, "Signed out.");
    }

    @GetMapping("/public/mkt/account/me")
    public ApiResponse<Map<String, Object>> me(@RequestHeader(value = SESSION, required = false) String token) {
        MarketplaceCustomer c = customers.authenticate(token);
        return ApiResponse.success(Map.of("name", c.getName(), "phone", c.getPhone(), "email", c.getEmail() == null ? "" : c.getEmail()));
    }

    @GetMapping("/public/mkt/account/orders")
    public ApiResponse<PageResponse<MarketplaceOrderDTOs.AccountOrderView>> orders(
            @RequestHeader(value = SESSION, required = false) String token,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.success(accounts.myOrders(customers.authenticate(token), page, size));
    }

    @PostMapping("/public/mkt/account/claim")
    public ApiResponse<MarketplaceOrderDTOs.AccountOrderView> claim(@RequestHeader(value = SESSION, required = false) String token,
            @RequestBody Claim b) {
        MarketplaceCustomer c = customers.authenticate(token);
        return ApiResponse.success(accounts.view(customers.claim(c, b.orderNo(), b.phone())), "Added to your orders.");
    }

    @PostMapping("/public/mkt/account/orders/{orderNo}/cancel")
    public ApiResponse<MarketplaceOrderDTOs.AccountOrderView> cancel(@RequestHeader(value = SESSION, required = false) String token,
            @PathVariable String orderNo, @RequestBody(required = false) Cancel b) {
        return ApiResponse.success(accounts.cancel(customers.authenticate(token), orderNo, b == null ? null : b.reason()),
                "Your order is cancelled.");
    }

    /** MKT-2b: the signed-in shopper's answer to an alternative seller, from My orders. */
    @PostMapping("/public/mkt/account/orders/{orderNo}/shortages/{id}/decision")
    public ApiResponse<MarketplaceOrderDTOs.OrderView> shortageDecision(@RequestHeader(value = SESSION, required = false) String token,
            @PathVariable String orderNo, @PathVariable Long id, @RequestBody(required = false) MarketplaceOrderDTOs.ShortageDecision b) {
        Boolean accept = b == null ? null : b.accept();
        MarketplaceOrderDTOs.OrderView v = shortages.decideAsCustomer(customers.authenticate(token).getId(), orderNo, id, accept);
        return ApiResponse.success(v, Boolean.TRUE.equals(accept)
                ? "Accepted. The new seller is asked to confirm." : "Declined. Your money for this part is returned.");
    }

    /** The checkout for a signed-in customer: the order is theirs by proof, and online payment is allowed. */
    @PostMapping("/public/mkt/account/checkout")
    public ApiResponse<MarketplaceOrderDTOs.OrderView> checkout(@RequestHeader(value = SESSION, required = false) String token,
            @RequestBody MarketplaceOrderDTOs.CheckoutRequest body) {
        MarketplaceOrderDTOs.OrderView v = checkout.checkout(body, customers.authenticate(token).getId());
        return ApiResponse.success(v, "CANCELLED".equals(v.status()) ? v.cancelReason()
                : "PAYMENT_PENDING".equals(v.status()) ? "We could not confirm your payment yet. If it was taken, it is refunded automatically."
                : "Waiting for " + (v.sellerName() == null ? "the seller" : v.sellerName()) + " to confirm.");
    }
}
