package com.myplus.welfare.service;

import java.util.List;

import jakarta.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Service;

import com.myplus.welfare.entity.Donation;
import com.myplus.welfare.repository.DonationRepo;

@Service
@Transactional
public class DonationService implements IDonationService {

    @Autowired
    private DonationRepo donationRepo;

    @Override
    public List<Donation> findAll() {
        return donationRepo.findAll();
    }

    @Override
    public List<Donation> findAll(Example<Donation> example) {
        return donationRepo.findAll(example);
    }

    @Override
    public Donation getOne(Long id) {
        return donationRepo.findById(id).orElse(null);
    }

    @Override
    public Donation save(Donation donation) {
        return donationRepo.save(donation);
    }

    @Override
    public void deleteById(Long id) {
        donationRepo.deleteById(id);
    }
}
