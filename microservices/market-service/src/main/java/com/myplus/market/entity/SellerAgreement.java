package com.myplus.market.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Which policy version a seller accepted, who accepted it and when. Append-only — never updated or deleted. */
@Entity
@Table(name = "seller_agreement")
@Getter @Setter
public class SellerAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_profile_id", nullable = false)
    private Long sellerProfileId;

    @Column(name = "seller_organization_id", nullable = false)
    private Long sellerOrganizationId;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(name = "policy_type", nullable = false, length = 32)
    private String policyType;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "accepted_by")
    private Long acceptedBy;

    @Column(name = "accepted_at", nullable = false)
    private LocalDateTime acceptedAt;
}
