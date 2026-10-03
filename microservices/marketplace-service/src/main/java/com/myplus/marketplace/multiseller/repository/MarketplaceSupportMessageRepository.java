package com.myplus.marketplace.multiseller.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceSupportMessage;

/** MKT-1f — the thread of a case (V30). idx_mkt_msg_case. */
public interface MarketplaceSupportMessageRepository extends JpaRepository<MarketplaceSupportMessage, Long> {

    /** Everything, for the operator. */
    List<MarketplaceSupportMessage> findByCaseIdOrderByIdAsc(Long caseId);

    /** What the customer may read: never an internal note or a seller's unrelayed reply. */
    List<MarketplaceSupportMessage> findByCaseIdAndVisibleToCustomerTrueOrderByIdAsc(Long caseId);
}
