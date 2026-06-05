package com.myplus.agriculture.service;

import java.util.List;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;

import com.myplus.agriculture.entity.AgricultureIncome;

public interface IAgricultureIncomeService {
    List<AgricultureIncome> findAll(Example<AgricultureIncome> example);
    List<AgricultureIncome> findAll(Example<AgricultureIncome> example, Sort sort);
    boolean exists(Example<AgricultureIncome> example);
    AgricultureIncome save(AgricultureIncome income);
    void deleteById(Long id);
}
