package com.myplus.agriculture.service;

import java.util.List;
import java.util.Optional;

import jakarta.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Service;

import com.myplus.agriculture.entity.Land;
import com.myplus.agriculture.repository.LandRepo;

@Service
@Transactional
public class LandService implements ILandService {

    @Autowired
    private LandRepo landRepo;

    @Override
    public List<Land> findAll() {
        return landRepo.findAll();
    }

    @Override
    public List<Land> findAll(Example<Land> example) {
        return landRepo.findAll(example);
    }

    @Override
    public boolean exists(Example<Land> example) {
        return landRepo.exists(example);
    }

    @Override
    public Optional<Land> findById(Long id) {
        return landRepo.findById(id);
    }

    @Override
    public Land save(Land land) {
        return landRepo.save(land);
    }

    @Override
    public void deleteById(Long id) {
        landRepo.deleteById(id);
    }
}
