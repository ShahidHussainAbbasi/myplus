package com.myplus.finance.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * F3 (GL): one chart-of-accounts line for a tenant — a code (1000, 4000…), a name, its {@link AccountType}, and the
 * {@link NormalSide} that increases it. Journal lines post against accounts; reports roll up by type. Org-scoped.
 */
@Entity
@Table(name = "accounts", indexes = {
        @Index(name = "idx_acct_org_code", columnList = "organization_id,code")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_side", nullable = false, length = 10)
    private NormalSide normalSide;

    @Column(name = "organization_id")
    private Long organizationId;
}
