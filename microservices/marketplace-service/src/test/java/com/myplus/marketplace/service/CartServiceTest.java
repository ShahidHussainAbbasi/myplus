package com.myplus.marketplace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.dto.CartDTO;
import com.myplus.marketplace.entity.Cart;
import com.myplus.marketplace.entity.CartItem;
import com.myplus.marketplace.entity.StorefrontCustomer;
import com.myplus.marketplace.repository.CartRepository;
import com.myplus.marketplace.repository.StorefrontCustomerRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Slice 68, E3 — pure Mockito (always runs). Cart add/update/remove, authoritative catalog pricing, and the
 * guest→account merge on login. cartRepo.save echoes its argument so the returned DTO reflects the mutation.
 */
@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    private static final Long ORG = 1L;
    private static final String ACTIVE = "ACTIVE";

    @Mock private CartRepository cartRepo;
    @Mock private CatalogClient catalogClient;
    @Mock private StorefrontCustomerRepository customerRepo;
    @InjectMocks private CartService service;

    private ProductRef ref(Long id, String name, String price) {
        return new ProductRef(id, "SKU" + id, name, "ea", new BigDecimal(price), BigDecimal.ZERO);
    }

    private CartItem line(Long productId, String name, String price, int qty) {
        return CartItem.builder().productId(productId).productName(name)
                .unitPrice(new BigDecimal(price)).quantity(qty).build();
    }

    private Cart cart(String token, Long accountId, CartItem... items) {
        List<CartItem> list = new ArrayList<>(List.of(items));
        return Cart.builder().id(99L).organizationId(ORG).cartToken(token)
                .customerAccountId(accountId).status(ACTIVE).items(list).build();
    }

    private CartDTO.Request req(String cartToken, String customerToken, Long productId, Integer qty) {
        return CartDTO.Request.builder().organizationId(ORG)
                .cartToken(cartToken).customerToken(customerToken).productId(productId).quantity(qty).build();
    }

    @Test
    void add_creates_and_prices_a_line_minting_a_token() {
        when(cartRepo.save(any(Cart.class))).thenAnswer(i -> i.getArgument(0));
        when(catalogClient.getProduct(10L)).thenReturn(ref(10L, "Cola", "2.50"));

        CartDTO d = service.add(req(null, null, 10L, 2));

        assertThat(d.getCartToken()).isNotBlank();           // minted for the new guest cart
        assertThat(d.getItems()).hasSize(1);
        assertThat(d.getItems().get(0).getName()).isEqualTo("Cola");
        assertThat(d.getItems().get(0).getUnitPrice()).isEqualByComparingTo("2.50");
        assertThat(d.getCount()).isEqualTo(2);
        assertThat(d.getSubtotal()).isEqualByComparingTo("5.00");
    }

    @Test
    void add_again_increments_existing_line() {
        Cart existing = cart("g", null, line(10L, "Cola", "2.50", 1));
        when(cartRepo.findByOrganizationIdAndCartTokenAndStatus(ORG, "g", ACTIVE)).thenReturn(Optional.of(existing));
        when(cartRepo.save(any(Cart.class))).thenAnswer(i -> i.getArgument(0));
        when(catalogClient.getProduct(10L)).thenReturn(ref(10L, "Cola", "2.50"));

        CartDTO d = service.add(req("g", null, 10L, 1));

        assertThat(d.getItems()).hasSize(1);
        assertThat(d.getItems().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void update_sets_quantity() {
        Cart existing = cart("g", null, line(10L, "Cola", "2.50", 3));
        when(cartRepo.findByOrganizationIdAndCartTokenAndStatus(ORG, "g", ACTIVE)).thenReturn(Optional.of(existing));
        when(cartRepo.save(any(Cart.class))).thenAnswer(i -> i.getArgument(0));

        CartDTO d = service.update(req("g", null, 10L, 5));

        assertThat(d.getItems().get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    void update_to_zero_removes_the_line() {
        Cart existing = cart("g", null, line(10L, "Cola", "2.50", 3));
        when(cartRepo.findByOrganizationIdAndCartTokenAndStatus(ORG, "g", ACTIVE)).thenReturn(Optional.of(existing));
        when(cartRepo.save(any(Cart.class))).thenAnswer(i -> i.getArgument(0));

        CartDTO d = service.update(req("g", null, 10L, 0));

        assertThat(d.getItems()).isEmpty();
    }

    @Test
    void remove_deletes_only_that_product() {
        Cart existing = cart("g", null, line(10L, "Cola", "2.50", 2), line(20L, "Juice", "3.00", 1));
        when(cartRepo.findByOrganizationIdAndCartTokenAndStatus(ORG, "g", ACTIVE)).thenReturn(Optional.of(existing));
        when(cartRepo.save(any(Cart.class))).thenAnswer(i -> i.getArgument(0));

        CartDTO d = service.remove(req("g", null, 10L, null));

        assertThat(d.getItems()).extracting(CartDTO.Line::getProductId).containsExactly(20L);
    }

    @Test
    void guest_cart_merges_into_account_cart_on_login() {
        StorefrontCustomer cust = StorefrontCustomer.builder().id(7L).organizationId(ORG).build();
        when(customerRepo.findBySessionToken("c")).thenReturn(Optional.of(cust));

        Cart guest = cart("g", null, line(10L, "Cola", "2.50", 2));
        guest.setId(1L);
        Cart account = cart("acct", 7L, line(20L, "Juice", "3.00", 1));
        account.setId(2L);
        when(cartRepo.findByOrganizationIdAndCartTokenAndStatus(ORG, "g", ACTIVE)).thenReturn(Optional.of(guest));
        when(cartRepo.findByOrganizationIdAndCustomerAccountIdAndStatus(ORG, 7L, ACTIVE)).thenReturn(Optional.of(account));
        when(cartRepo.save(any(Cart.class))).thenAnswer(i -> i.getArgument(0));
        when(catalogClient.getProduct(30L)).thenReturn(ref(30L, "Water", "1.00"));

        CartDTO d = service.add(req("g", "c", 30L, 1));

        assertThat(d.isCustomerLinked()).isTrue();
        assertThat(d.getItems()).extracting(CartDTO.Line::getProductId)
                .containsExactlyInAnyOrder(20L, 10L, 30L);     // account's own + merged guest + the new add
        assertThat(guest.getStatus()).isEqualTo("CONVERTED");  // guest cart consumed by the merge
    }

    @Test
    void add_rejects_unknown_product() {
        when(cartRepo.save(any(Cart.class))).thenAnswer(i -> i.getArgument(0));
        when(catalogClient.getProduct(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.add(req(null, null, 99L, 1)))
                .isInstanceOf(ValidationException.class);
    }
}
