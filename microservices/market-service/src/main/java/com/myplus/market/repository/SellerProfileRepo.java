package com.myplus.market.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.market.entity.SellerProfile;

public interface SellerProfileRepo extends JpaRepository<SellerProfile, Long> {

    Optional<SellerProfile> findBySellerOrganizationId(Long sellerOrganizationId);

    List<SellerProfile> findByStatusOrderByAppliedAtAsc(String status);

    List<SellerProfile> findAllByOrderByAppliedAtDesc();
}
