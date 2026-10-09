package com.myplus.expense.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.expense.entity.ExpenseCategory;

/** Categories, always read inside one tenant. */
public interface ExpenseCategoryRepo extends JpaRepository<ExpenseCategory, Long> {

    List<ExpenseCategory> findByOrganizationIdOrderBySortOrderAscNameAsc(Long organizationId);

    Optional<ExpenseCategory> findByIdAndOrganizationId(Long id, Long organizationId);

    boolean existsByOrganizationIdAndCode(Long organizationId, String code);

    long countByOrganizationId(Long organizationId);

    /** EX-2e — two categories with the same name would be one choice the owner cannot tell apart. */
    boolean existsByOrganizationIdAndNameIgnoreCase(Long organizationId, String name);

    boolean existsByOrganizationIdAndNameIgnoreCaseAndIdNot(Long organizationId, String name, Long id);

    /** EX-2e — at least one category must stay on, or nobody can record an expense. */
    long countByOrganizationIdAndActiveTrue(Long organizationId);
}
