package com.myplus.agriculture.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.agriculture.entity.AgricultureIncome;

public interface AgricultureIncomeRepo extends JpaRepository<AgricultureIncome, Long>, QueryByExampleExecutor<AgricultureIncome> {
}
