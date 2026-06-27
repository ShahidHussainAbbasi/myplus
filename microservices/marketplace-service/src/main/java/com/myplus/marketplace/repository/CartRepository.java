package com.myplus.marketplace.repository;

import com.myplus.marketplace.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Persistent storefront carts (slice 68, E3). Carts are addressed by an opaque token, scoped to the store org. */
@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {

    /** The ACTIVE cart for a guest token within a store. */
    Optional<Cart> findByOrganizationIdAndCartTokenAndStatus(Long organizationId, String cartToken, String status);

    /** The ACTIVE cart linked to a logged-in shopper within a store (for guest→account merge on login). */
    Optional<Cart> findByOrganizationIdAndCustomerAccountIdAndStatus(Long organizationId, Long customerAccountId, String status);
}
