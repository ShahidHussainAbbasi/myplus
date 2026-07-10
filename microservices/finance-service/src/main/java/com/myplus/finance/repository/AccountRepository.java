package com.myplus.finance.repository;

import com.myplus.finance.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** F3 (GL): the tenant's chart of accounts. GL entries are always org-stamped (from CurrentUser), so org-scoped. */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByOrganizationIdOrderByCodeAsc(Long organizationId);

    long countByOrganizationId(Long organizationId);

    Optional<Account> findByOrganizationIdAndCode(Long organizationId, String code);

    Optional<Account> findByIdAndOrganizationId(Long id, Long organizationId);
}
