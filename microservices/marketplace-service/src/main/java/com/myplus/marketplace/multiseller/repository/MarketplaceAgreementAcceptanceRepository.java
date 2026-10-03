package com.myplus.marketplace.multiseller.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceAgreementAcceptance;

public interface MarketplaceAgreementAcceptanceRepository extends JpaRepository<MarketplaceAgreementAcceptance, Long> {

    Optional<MarketplaceAgreementAcceptance> findByOrganizationIdAndAgreementCodeAndAgreementVersion(
            Long organizationId, String agreementCode, String agreementVersion);

    List<MarketplaceAgreementAcceptance> findByOrganizationIdOrderByAcceptedAtDesc(Long organizationId);
}
