package com.myplus.welfare.service;

import java.util.List;

import jakarta.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Service;

import com.myplus.welfare.entity.Donator;
import com.myplus.welfare.repository.DonatorRepo;

@Service
@Transactional
public class DonatorService implements IDonatorService {

    @Autowired
    private DonatorRepo donatorRepo;

    @Override
    public List<Donator> findAll(Example<Donator> example) {
        return donatorRepo.findAll(example);
    }

    @Override
    public List<Donator> findScoped(Long orgId, Long userId) {
        return donatorRepo.findScoped(orgId, userId);
    }

    @Override
    public boolean exists(Example<Donator> example) {
        return donatorRepo.exists(example);
    }

    @Override
    public Donator getOne(Long id) {
        return donatorRepo.findById(id).orElse(null);
    }

    @Override
    public Donator save(Donator donator) {
        return donatorRepo.save(donator);
    }

    @Override
    public void deleteById(Long id) {
        donatorRepo.deleteById(id);
    }
}
