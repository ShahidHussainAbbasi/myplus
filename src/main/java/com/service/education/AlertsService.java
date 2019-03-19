package com.service.education;

import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.education.AlertsRepo;
import com.persistence.model.education.Alerts;

@Service
@Transactional
public class AlertsService implements IAlertsService {


    @Autowired
    AlertsRepo repo;
    
	@Override
	public List<Alerts> findAll() {
		// TODO Auto-generated method stub
		return repo.findAll();
	}

	@Override
	public List<Alerts> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return repo.findAll(sort);
	}

	@Override
	public List<Alerts> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return repo.findAllById(ids);
	}

	@Override
	public <S extends Alerts> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return repo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		repo.flush();
	}

	@Override
	public <S extends Alerts> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return repo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Alerts> entities) {
		// TODO Auto-generated method stub
		repo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		repo.deleteAllInBatch();
	}

	@Override
	public Alerts getOne(Long id) {
		// TODO Auto-generated method stub
		return repo.getOne(id);
	}

	@Override
	public <S extends Alerts> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return repo.findAll(example);
	}

	@Override
	public <S extends Alerts> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return repo.findAll(example, sort);
	}

	@Override
	public Page<Alerts> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return repo.findAll(pageable);
	}

	@Override
	public <S extends Alerts> S save(S entity) {
		// TODO Auto-generated method stub
		return repo.save(entity);
	}

	@Override
	public Optional<Alerts> findById(Long id) {
		// TODO Auto-generated method stub
		return repo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return repo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return repo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		repo.deleteById(id);
	}

	@Override
	public void delete(Alerts entity) {
		// TODO Auto-generated method stub
		repo.delete(entity);
	}

	@Override
	public void deleteAll(Iterable<? extends Alerts> entities) {
		// TODO Auto-generated method stub
		repo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		repo.deleteAll();
	}

	@Override
	public <S extends Alerts> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return repo.findOne(example);
	}

	@Override
	public <S extends Alerts> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return repo.findAll(example, pageable);
	}

	@Override
	public <S extends Alerts> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return repo.count(example);
	}

	@Override
	public <S extends Alerts> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return repo.exists(example);
	}

//	@Override
//	public int updateStatus(String status, String id) {
//		// TODO Auto-generated method stub
//		return updateStatus(status, id);
//	}

    
}