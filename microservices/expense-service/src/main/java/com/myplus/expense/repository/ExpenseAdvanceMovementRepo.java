package com.myplus.expense.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.expense.entity.ExpenseAdvanceMovement;

/** EX-7b — advances given and taken back. */
public interface ExpenseAdvanceMovementRepo extends JpaRepository<ExpenseAdvanceMovement, Long> {

    Optional<ExpenseAdvanceMovement> findByOrganizationIdAndIdempotencyKey(Long organizationId, String idempotencyKey);

    Optional<ExpenseAdvanceMovement> findByIdAndOrganizationId(Long id, Long organizationId);

    List<ExpenseAdvanceMovement> findTop50ByOrganizationIdAndUserIdOrderByIdDesc(Long organizationId, Long userId);

    List<ExpenseAdvanceMovement> findTop50ByStatusAndCreatedAtBeforeOrderByIdAsc(String status, LocalDateTime before);
}
