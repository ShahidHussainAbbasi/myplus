package com.myplus.expense.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.expense.entity.ExpenseAdvanceBalance;

import jakarta.persistence.LockModeType;

/** EX-7b — members' advance balances, always within one tenant. */
public interface ExpenseAdvanceBalanceRepo extends JpaRepository<ExpenseAdvanceBalance, Long> {

    Optional<ExpenseAdvanceBalance> findByOrganizationIdAndUserId(Long organizationId, Long userId);

    /** The row every change to a member's advance holds for the rest of its transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM ExpenseAdvanceBalance b WHERE b.organizationId = :org AND b.userId = :userId")
    Optional<ExpenseAdvanceBalance> lock(@Param("org") Long org, @Param("userId") Long userId);

    List<ExpenseAdvanceBalance> findByOrganizationIdOrderByMemberNameAsc(Long organizationId);
}
