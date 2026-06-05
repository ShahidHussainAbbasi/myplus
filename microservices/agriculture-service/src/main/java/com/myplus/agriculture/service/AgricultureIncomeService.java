package com.myplus.agriculture.service;

import java.util.List;

import jakarta.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.myplus.agriculture.entity.AgricultureIncome;
import com.myplus.agriculture.repository.AgricultureIncomeRepo;

@Service
@Transactional
public class AgricultureIncomeService implements IAgricultureIncomeService {

    @Autowired
    private AgricultureIncomeRepo incomeRepo;

    @Override
    public List<AgricultureIncome> findAll(Example<AgricultureIncome> example) {
        return incomeRepo.findAll(example);
    }

    @Override
    public List<AgricultureIncome> findAll(Example<AgricultureIncome> example, Sort sort) {
        return incomeRepo.findAll(example, sort);
    }

    @Override
    public boolean exists(Example<AgricultureIncome> example) {
        return incomeRepo.exists(example);
    }

    @Override
    public AgricultureIncome save(AgricultureIncome income) {
        return incomeRepo.save(income);
    }

    @Override
    public void deleteById(Long id) {
        incomeRepo.deleteById(id);
    }
}
