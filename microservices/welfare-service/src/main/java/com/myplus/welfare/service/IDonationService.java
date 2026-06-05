package com.myplus.welfare.service;

import java.util.List;

import org.springframework.data.domain.Example;

import com.myplus.welfare.entity.Donation;

public interface IDonationService {
    List<Donation> findAll();
    List<Donation> findAll(Example<Donation> example);
    Donation getOne(Long id);
    Donation save(Donation donation);
    void deleteById(Long id);
}
