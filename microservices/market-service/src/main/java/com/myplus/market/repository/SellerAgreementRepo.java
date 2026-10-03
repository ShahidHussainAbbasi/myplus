package com.myplus.market.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.market.entity.SellerAgreement;

public interface SellerAgreementRepo extends JpaRepository<SellerAgreement, Long> {

    boolean existsBySellerProfileIdAndPolicyId(Long sellerProfileId, Long policyId);

    List<SellerAgreement> findBySellerProfileIdOrderByAcceptedAtDesc(Long sellerProfileId);
}
