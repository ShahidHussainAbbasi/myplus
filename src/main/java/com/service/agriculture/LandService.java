package com.service.agriculture;

import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.agriculture.LandRepository;
import com.persistence.model.agriculture.Land;

@Service
@Transactional
public class LandService implements ILandService {

	@Autowired
	LandRepository landRepo;
	@Override
	public List<Land> findAll() {
		return landRepo.findAll();
	}

	@Override
	public List<Land> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<Land> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return landRepo.findAllById(ids);
	}

	@Override
	public <S extends Land> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return landRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		landRepo.flush();
	}

	@Override
	public <S extends Land> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void deleteInBatch(Iterable<Land> entities) {
		// TODO Auto-generated method stub
		landRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		landRepo.deleteAllInBatch();
	}

	@Override
	public Land getOne(Long id) {
		// TODO Auto-generated method stub
		return landRepo.getOne(id);
	}

	@Override
	public <S extends Land> List<S> findAll(Example<S> example) {
		return landRepo.findAll(example);
	}

	@Override
	public <S extends Land> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Page<Land> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends Land> S save(S entity) {
		return landRepo.save(entity);
	}

	@Override
	public Optional<Land> findById(Long id) {
		return landRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return landRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return landRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		landRepo.deleteById(id);	
	}

	@Override
	public void delete(Land entity) {
		landRepo.delete(entity);
	}

	@Override
	public void deleteAll(Iterable<? extends Land> entities) {
		// TODO Auto-generated method stub
		landRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public <S extends Land> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return landRepo.findOne(example);
	}

	@Override
	public <S extends Land> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends Land> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public <S extends Land> boolean exists(Example<S> example) {
		return landRepo.exists(example);
	}


}