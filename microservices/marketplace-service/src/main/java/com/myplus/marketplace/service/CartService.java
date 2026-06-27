package com.myplus.marketplace.service;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.dto.CartDTO;
import com.myplus.marketplace.entity.Cart;
import com.myplus.marketplace.entity.CartItem;
import com.myplus.marketplace.entity.StorefrontCustomer;
import com.myplus.marketplace.repository.CartRepository;
import com.myplus.marketplace.repository.StorefrontCustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Persistent storefront cart (slice 68, E3). Server owns the cart; the browser only keeps an opaque {@code cartToken}.
 * Lines are priced authoritatively from catalog at add-time (client prices are never trusted). A guest cart merges
 * into the shopper's account cart on login. org/token scope every operation. Stock is NOT held here — reservation
 * happens at checkout via the existing E7 saga.
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private static final String ACTIVE = "ACTIVE";
    private static final String CONVERTED = "CONVERTED";
    /** Synthetic actor so catalog calls carry X-Org-Id for the store (the shopper is anonymous). */
    private static final Long STOREFRONT_USER = 0L;

    private final CartRepository cartRepo;
    private final CatalogClient catalogClient;
    private final StorefrontCustomerRepository customerRepo;

    /** Read-only cart view — never creates a row (so merely visiting the store doesn't spawn empty carts). */
    @Transactional(readOnly = true)
    public CartDTO view(CartDTO.Request r) {
        requireOrg(r.getOrganizationId());
        Long accountId = resolveAccount(r.getOrganizationId(), r.getCustomerToken());
        Cart cart = null;
        if (accountId != null) {
            cart = cartRepo.findByOrganizationIdAndCustomerAccountIdAndStatus(r.getOrganizationId(), accountId, ACTIVE)
                    .orElse(null);
        }
        if (cart == null && hasText(r.getCartToken())) {
            cart = cartRepo.findByOrganizationIdAndCartTokenAndStatus(r.getOrganizationId(), r.getCartToken().trim(), ACTIVE)
                    .orElse(null);
        }
        if (cart == null) {
            return CartDTO.builder().cartToken(r.getCartToken()).customerLinked(accountId != null)
                    .items(new ArrayList<>()).subtotal(BigDecimal.ZERO).count(0).build();
        }
        return toDTO(cart);
    }

    @Transactional
    public CartDTO add(CartDTO.Request r) {
        if (r.getProductId() == null) throw new ValidationException("productId is required");
        Cart cart = getOrCreate(r.getOrganizationId(), r.getCartToken(), r.getCustomerToken());
        int qty = (r.getQuantity() == null || r.getQuantity() < 1) ? 1 : r.getQuantity();

        ProductRef p = asStore(cart.getOrganizationId(), () -> catalogClient.getProduct(r.getProductId()));
        if (p == null || p.getId() == null) throw new ValidationException("Product not found");

        CartItem line = findLine(cart, r.getProductId());
        if (line == null) {
            cart.getItems().add(CartItem.builder()
                    .productId(p.getId())
                    .productName(p.getName())
                    .unitPrice(p.getSellingPrice() != null ? p.getSellingPrice() : BigDecimal.ZERO)
                    .quantity(qty)
                    .build());
        } else {
            line.setQuantity(line.getQuantity() + qty);
        }
        return toDTO(cartRepo.save(cart));
    }

    @Transactional
    public CartDTO update(CartDTO.Request r) {
        if (r.getProductId() == null) throw new ValidationException("productId is required");
        Cart cart = getOrCreate(r.getOrganizationId(), r.getCartToken(), r.getCustomerToken());
        CartItem line = findLine(cart, r.getProductId());
        if (line != null) {
            int qty = r.getQuantity() == null ? 0 : r.getQuantity();
            if (qty <= 0) cart.getItems().remove(line);
            else line.setQuantity(qty);
        }
        return toDTO(cartRepo.save(cart));
    }

    @Transactional
    public CartDTO remove(CartDTO.Request r) {
        Cart cart = getOrCreate(r.getOrganizationId(), r.getCartToken(), r.getCustomerToken());
        if (r.getProductId() != null) {
            cart.getItems().removeIf(i -> r.getProductId().equals(i.getProductId()));
        }
        return toDTO(cartRepo.save(cart));
    }

    /** Checkout hand-off (slice 68): close the cart once its order is placed so a stale cart can't be re-ordered.
     *  Best-effort — a missing/closed cart is a no-op. */
    @Transactional
    public void markConverted(Long org, String cartToken) {
        if (org == null || !hasText(cartToken)) return;
        cartRepo.findByOrganizationIdAndCartTokenAndStatus(org, cartToken.trim(), ACTIVE)
                .ifPresent(c -> { c.setStatus(CONVERTED); cartRepo.save(c); });
    }

    // --- internals ---------------------------------------------------------------------------------------------

    /** Resolve the cart to mutate: the logged-in shopper's cart (merging a guest cart in), else the guest cart,
     *  else a fresh cart with a minted token. */
    private Cart getOrCreate(Long org, String cartToken, String customerToken) {
        requireOrg(org);
        Long accountId = resolveAccount(org, customerToken);

        Cart guest = hasText(cartToken)
                ? cartRepo.findByOrganizationIdAndCartTokenAndStatus(org, cartToken.trim(), ACTIVE).orElse(null)
                : null;

        if (accountId != null) {
            Cart account = cartRepo.findByOrganizationIdAndCustomerAccountIdAndStatus(org, accountId, ACTIVE)
                    .orElse(null);
            if (account == null) {
                if (guest != null) {                 // promote the guest cart to the account
                    guest.setCustomerAccountId(accountId);
                    return cartRepo.save(guest);
                }
                return cartRepo.save(newCart(org, accountId));
            }
            if (guest != null && !guest.getId().equals(account.getId())) {   // merge a distinct guest cart in
                mergeInto(account, guest);
                guest.setStatus(CONVERTED);          // consumed by the merge
                cartRepo.save(guest);
                account = cartRepo.save(account);
            }
            return account;
        }

        if (guest != null) return guest;
        return cartRepo.save(newCart(org, null));
    }

    private Cart newCart(Long org, Long accountId) {
        return Cart.builder()
                .organizationId(org)
                .cartToken(UUID.randomUUID().toString())
                .customerAccountId(accountId)
                .status(ACTIVE)
                .items(new ArrayList<>())
                .build();
    }

    /** Fold guest lines into the account cart: same product → sum quantity; new product → copy the line. */
    private void mergeInto(Cart account, Cart guest) {
        for (CartItem g : guest.getItems()) {
            CartItem existing = findLine(account, g.getProductId());
            if (existing != null) {
                existing.setQuantity(existing.getQuantity() + g.getQuantity());
            } else {
                account.getItems().add(CartItem.builder()
                        .productId(g.getProductId()).productName(g.getProductName())
                        .unitPrice(g.getUnitPrice()).quantity(g.getQuantity()).build());
            }
        }
    }

    private Long resolveAccount(Long org, String customerToken) {
        if (!hasText(customerToken)) return null;
        return customerRepo.findBySessionToken(customerToken.trim())
                .filter(c -> org.equals(c.getOrganizationId()))
                .map(StorefrontCustomer::getId)
                .orElse(null);
    }

    private CartItem findLine(Cart cart, Long productId) {
        if (productId == null) return null;
        return cart.getItems().stream().filter(i -> productId.equals(i.getProductId())).findFirst().orElse(null);
    }

    private CartDTO toDTO(Cart cart) {
        List<CartDTO.Line> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        int count = 0;
        for (CartItem i : cart.getItems()) {
            BigDecimal unit = i.getUnitPrice() != null ? i.getUnitPrice() : BigDecimal.ZERO;
            int qty = i.getQuantity() != null ? i.getQuantity() : 0;
            BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(qty));
            subtotal = subtotal.add(lineTotal);
            count += qty;
            lines.add(CartDTO.Line.builder()
                    .productId(i.getProductId()).name(i.getProductName())
                    .unitPrice(unit).quantity(qty).lineTotal(lineTotal).build());
        }
        return CartDTO.builder()
                .cartToken(cart.getCartToken())
                .customerLinked(cart.getCustomerAccountId() != null)
                .items(lines).subtotal(subtotal).count(count)
                .build();
    }

    private <T> T asStore(Long org, Supplier<T> call) {
        AtomicReference<T> out = new AtomicReference<>();
        GatewayIdentityForwarding.runAs(STOREFRONT_USER, org, () -> out.set(call.get()));
        return out.get();
    }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }
    private static void requireOrg(Long org) {
        if (org == null) throw new ValidationException("Store (organizationId) is required");
    }
}
