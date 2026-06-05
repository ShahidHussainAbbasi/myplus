package com.myplus.agriculture.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.agriculture.entity.AgricultureExpense;

public interface AgricultureExpenseRepo extends JpaRepository<AgricultureExpense, Long>, QueryByExampleExecutor<AgricultureExpense> {
}
