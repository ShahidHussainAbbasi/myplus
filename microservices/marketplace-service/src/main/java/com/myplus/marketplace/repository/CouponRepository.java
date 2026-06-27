package com.myplus.marketplace.repository;

import com.myplus.marketplace.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Store promo codes (slice 72, E13), org-scoped. */
@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByOrganizationIdAndCode(Long organizationId, String code);

    List<Coupon> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);
}
