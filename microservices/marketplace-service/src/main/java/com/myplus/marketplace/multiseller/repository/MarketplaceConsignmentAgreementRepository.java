package com.myplus.marketplace.multiseller.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceConsignmentAgreement;

/** MKT-5a — consignment agreements. Every reader is listed in the slice doc's trace. */
public interface MarketplaceConsignmentAgreementRepository extends JpaRepository<MarketplaceConsignmentAgreement, Long> {

    /** The operator's list, newest first. */
    List<MarketplaceConsignmentAgreement> findAllByOrderByIdDesc(Pageable page);

    List<MarketplaceConsignmentAgreement> findByStatusOrderByIdDesc(String status, Pageable page);

    /** A business's own agreements, as consignor or as consignee, newest first. */
    List<MarketplaceConsignmentAgreement> findByConsignorOrgIdOrConsigneeOrgIdOrderByIdDesc(Long consignor, Long consignee,
            Pageable page);

    /** The pair's agreement in one status: at most one OFFERED and one ACTIVE at a time (the service keeps it so). */
    Optional<MarketplaceConsignmentAgreement> findFirstByConsignorOrgIdAndConsigneeOrgIdAndStatusOrderByIdDesc(
            Long consignor, Long consignee, String status);
}
