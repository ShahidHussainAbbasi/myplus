package com.myplus.agriculture.service;

import java.util.List;

import jakarta.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.myplus.agriculture.entity.AgricultureExpense;
import com.myplus.agriculture.repository.AgricultureExpenseRepo;

@Service
@Transactional
public class AgricultureExpenseService implements IAgricultureExpenseService {

    @Autowired
    private AgricultureExpenseRepo expenseRepo;

    @Override
    public List<AgricultureExpense> findAll(Example<AgricultureExpense> example) {
        return expenseRepo.findAll(example);
    }

    @Override
    public List<AgricultureExpense> findAll(Example<AgricultureExpense> example, Sort sort) {
        return expenseRepo.findAll(example, sort);
    }

    @Override
    public boolean exists(Example<AgricultureExpense> example) {
        return expenseRepo.exists(example);
    }

    @Override
    public AgricultureExpense save(AgricultureExpense expense) {
        return expenseRepo.save(expense);
    }

    @Override
    public void deleteById(Long id) {
        expenseRepo.deleteById(id);
    }
}
