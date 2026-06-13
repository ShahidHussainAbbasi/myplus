package com.myplus.agriculture.service;

import java.util.List;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;

import com.myplus.agriculture.entity.AgricultureExpense;

public interface IAgricultureExpenseService {
    List<AgricultureExpense> findAll(Example<AgricultureExpense> example);
    List<AgricultureExpense> findAll(Example<AgricultureExpense> example, Sort sort);
    /** Tenant-scoped expense (own org + caller's pre-migration org-NULL rows). */
    List<AgricultureExpense> findScoped(Long orgId, Long userId);
    boolean exists(Example<AgricultureExpense> example);
    AgricultureExpense save(AgricultureExpense expense);
    void deleteById(Long id);
}
