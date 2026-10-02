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
}
